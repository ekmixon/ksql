/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.engine;

import static io.confluent.ksql.metastore.model.DataSource.DataSourceType;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.confluent.ksql.KsqlExecutionContext.ExecuteResult;
import io.confluent.ksql.analyzer.ImmutableAnalysis;
import io.confluent.ksql.config.SessionConfig;
import io.confluent.ksql.execution.ddl.commands.CreateTableCommand;
import io.confluent.ksql.execution.ddl.commands.DdlCommand;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.plan.ExecutionStep;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.PlanInfo;
import io.confluent.ksql.execution.plan.PlanInfoExtractor;
import io.confluent.ksql.execution.streams.RoutingOptions;
import io.confluent.ksql.function.InternalFunctionRegistry;
import io.confluent.ksql.internal.PullQueryExecutorMetrics;
import io.confluent.ksql.internal.ScalablePushQueryMetrics;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.MetaStoreImpl;
import io.confluent.ksql.metastore.MutableMetaStore;
import io.confluent.ksql.metastore.model.DataSource;
import io.confluent.ksql.metastore.model.KsqlTable;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.OutputRefinement;
import io.confluent.ksql.parser.tree.AliasedRelation;
import io.confluent.ksql.parser.tree.CreateAsSelect;
import io.confluent.ksql.parser.tree.CreateStream;
import io.confluent.ksql.parser.tree.CreateStreamAsSelect;
import io.confluent.ksql.parser.tree.CreateTable;
import io.confluent.ksql.parser.tree.CreateTableAsSelect;
import io.confluent.ksql.parser.tree.ExecutableDdlStatement;
import io.confluent.ksql.parser.tree.Query;
import io.confluent.ksql.parser.tree.QueryContainer;
import io.confluent.ksql.parser.tree.Relation;
import io.confluent.ksql.parser.tree.Select;
import io.confluent.ksql.parser.tree.SingleColumn;
import io.confluent.ksql.parser.tree.Sink;
import io.confluent.ksql.parser.tree.Statement;
import io.confluent.ksql.parser.tree.Table;
import io.confluent.ksql.parser.tree.TableElement;
import io.confluent.ksql.physical.PhysicalPlan;
import io.confluent.ksql.physical.pull.HARouting;
import io.confluent.ksql.physical.pull.PullPhysicalPlan;
import io.confluent.ksql.physical.pull.PullPhysicalPlanBuilder;
import io.confluent.ksql.physical.pull.PullQueryQueuePopulator;
import io.confluent.ksql.physical.pull.PullQueryResult;
import io.confluent.ksql.physical.scalablepush.PushPhysicalPlan;
import io.confluent.ksql.physical.scalablepush.PushPhysicalPlanBuilder;
import io.confluent.ksql.physical.scalablepush.PushQueryPreparer;
import io.confluent.ksql.physical.scalablepush.PushQueryQueuePopulator;
import io.confluent.ksql.physical.scalablepush.PushRouting;
import io.confluent.ksql.physical.scalablepush.PushRoutingOptions;
import io.confluent.ksql.planner.LogicalPlanNode;
import io.confluent.ksql.planner.LogicalPlanner;
import io.confluent.ksql.planner.QueryPlannerOptions;
import io.confluent.ksql.planner.plan.DataSourceNode;
import io.confluent.ksql.planner.plan.KsqlBareOutputNode;
import io.confluent.ksql.planner.plan.KsqlStructuredDataOutputNode;
import io.confluent.ksql.planner.plan.OutputNode;
import io.confluent.ksql.planner.plan.PlanNode;
import io.confluent.ksql.query.PullQueryQueue;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.query.QueryRegistry;
import io.confluent.ksql.query.TransientQueryQueue;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.utils.FormatOptions;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.RefinementInfo;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.statement.ConfiguredStatement;
import io.confluent.ksql.util.ConsistencyOffsetVector;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlConstants;
import io.confluent.ksql.util.KsqlConstants.RoutingNodeType;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.KsqlStatementException;
import io.confluent.ksql.util.PersistentQueryMetadata;
import io.confluent.ksql.util.PlanSummary;
import io.confluent.ksql.util.PushQueryMetadata;
import io.confluent.ksql.util.PushQueryMetadata.ResultType;
import io.confluent.ksql.util.ScalablePushQueryMetadata;
import io.confluent.ksql.util.TransientQueryMetadata;
import io.vertx.core.Context;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executor of {@code PreparedStatement} within a specific {@code EngineContext} and using a
 * specific set of config.
 * </p>
 * All statements are executed using a {@code ServiceContext} specified in the constructor. This
 * {@code ServiceContext} might have been initialized with limited permissions to access Kafka
 * resources. The {@code EngineContext} has an internal {@code ServiceContext} that might have more
 * or less permissions than the one specified. This approach is useful when KSQL needs to
 * impersonate the current REST user executing the statements.
 */
// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
final class EngineExecutor {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling

  private static final Logger LOG = LoggerFactory.getLogger(EngineExecutor.class);
  private static final String NO_OUTPUT_TOPIC_PREFIX = "";

  private final EngineContext engineContext;
  private final ServiceContext serviceContext;
  private final SessionConfig config;

  private EngineExecutor(
      final EngineContext engineContext,
      final ServiceContext serviceContext,
      final SessionConfig config
  ) {
    this.engineContext = Objects.requireNonNull(engineContext, "engineContext");
    this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    this.config = Objects.requireNonNull(config, "config");

    KsqlEngineProps.throwOnImmutableOverride(config.getOverrides());
  }

  static EngineExecutor create(
      final EngineContext engineContext,
      final ServiceContext serviceContext,
      final SessionConfig config
  ) {
    return new EngineExecutor(engineContext, serviceContext, config);
  }

  ExecuteResult execute(final KsqlPlan plan) {
    if (!plan.getQueryPlan().isPresent()) {
      final String ddlResult = plan
          .getDdlCommand()
          .map(ddl -> executeDdl(ddl, plan.getStatementText(), false, Collections.emptySet()))
          .orElseThrow(
              () -> new IllegalStateException(
                  "DdlResult should be present if there is no physical plan."));
      return ExecuteResult.of(ddlResult);
    }

    final QueryPlan queryPlan = plan.getQueryPlan().get();
    final KsqlConstants.PersistentQueryType persistentQueryType =
        plan.getPersistentQueryType().get();

    // CREATE_SOURCE do not write to any topic. We check for read-only topics only for queries
    // that attempt to write to a sink (i.e. INSERT or CREATE_AS).
    if (persistentQueryType != KsqlConstants.PersistentQueryType.CREATE_SOURCE) {
      final DataSource sinkSource = engineContext.getMetaStore()
          .getSource(queryPlan.getSink().get());

      if (sinkSource != null && sinkSource.isSource()) {
        throw new KsqlException(String.format("Cannot insert into read-only %s: %s",
            sinkSource.getDataSourceType().getKsqlType().toLowerCase(),
            sinkSource.getName().text()));
      }
    }

    final Optional<String> ddlResult = plan.getDdlCommand().map(ddl ->
        executeDdl(ddl, plan.getStatementText(), true, queryPlan.getSources()));

    // Return if the source to create already exists.
    if (ddlResult.isPresent() && ddlResult.get().contains("already exists")) {
      return ExecuteResult.of(ddlResult.get());
    }

    // Do not execute the plan (found on new CST commands or commands read from the command topic)
    // for source tables if the feature is disabled. CST will still be read-only, but no query
    // must be executed.
    if (persistentQueryType == KsqlConstants.PersistentQueryType.CREATE_SOURCE
        && !isSourceTableMaterializationEnabled()) {
      LOG.info(String.format(
          "Source table query '%s' won't be materialized because '%s' is disabled.",
          plan.getStatementText(),
          KsqlConfig.KSQL_SOURCE_TABLE_MATERIALIZATION_ENABLED));
      return ExecuteResult.of(ddlResult.get());
    }

    return ExecuteResult.of(executePersistentQuery(
        queryPlan,
        plan.getStatementText(),
        persistentQueryType)
    );
  }

  /**
   * Evaluates a pull query by first analyzing it, then building the logical plan and finally
   * the physical plan. The execution is then done using the physical plan in a pipelined manner.
   * @param statement The pull query
   * @param routingOptions Configuration parameters used for HA routing
   * @param pullQueryMetrics JMX metrics
   * @return the rows that are the result of evaluating the pull query
   */
  PullQueryResult executeTablePullQuery(
      final ImmutableAnalysis analysis,
      final ConfiguredStatement<Query> statement,
      final HARouting routing,
      final RoutingOptions routingOptions,
      final QueryPlannerOptions queryPlannerOptions,
      final Optional<PullQueryExecutorMetrics> pullQueryMetrics,
      final boolean startImmediately,
      final Optional<ConsistencyOffsetVector> consistencyOffsetVector
  ) {

    if (!statement.getStatement().isPullQuery()) {
      throw new IllegalArgumentException("Executor can only handle pull queries");
    }
    final SessionConfig sessionConfig = statement.getSessionConfig();

    // If we ever change how many hops a request can do, we'll need to update this for correct
    // metrics.
    final RoutingNodeType routingNodeType = routingOptions.getIsSkipForwardRequest()
        ? RoutingNodeType.REMOTE_NODE : RoutingNodeType.SOURCE_NODE;

    PullPhysicalPlan plan = null;

    try {
      // Do not set sessionConfig.getConfig to true! The copying is inefficient and slows down pull
      // query performance significantly.  Instead use QueryPlannerOptions which check overrides
      // deliberately.
      final KsqlConfig ksqlConfig = sessionConfig.getConfig(false);
      final LogicalPlanNode logicalPlan = buildAndValidateLogicalPlan(
          statement, analysis, ksqlConfig, queryPlannerOptions, false);

      // This is a cancel signal that is used to stop both local operations and requests
      final CompletableFuture<Void> shouldCancelRequests = new CompletableFuture<>();

      plan = buildPullPhysicalPlan(
          logicalPlan,
          analysis,
          queryPlannerOptions,
          shouldCancelRequests
      );
      final PullPhysicalPlan physicalPlan = plan;

      final PullQueryQueue pullQueryQueue = new PullQueryQueue();
      final PullQueryQueuePopulator populator = () -> routing.handlePullQuery(
          serviceContext,
          physicalPlan, statement, routingOptions, physicalPlan.getOutputSchema(),
          physicalPlan.getQueryId(), pullQueryQueue, shouldCancelRequests, consistencyOffsetVector);
      final PullQueryResult result = new PullQueryResult(physicalPlan.getOutputSchema(), populator,
          physicalPlan.getQueryId(), pullQueryQueue, pullQueryMetrics, physicalPlan.getSourceType(),
          physicalPlan.getPlanType(), routingNodeType, physicalPlan::getRowsReadFromDataSource,
          shouldCancelRequests, consistencyOffsetVector);
      if (startImmediately) {
        result.start();
      }
      return result;
    } catch (final Exception e) {
      if (plan == null) {
        pullQueryMetrics.ifPresent(m -> m.recordErrorRateForNoResult(1));
      } else {
        final PullPhysicalPlan physicalPlan = plan;
        pullQueryMetrics.ifPresent(metrics -> metrics.recordErrorRate(1,
            physicalPlan.getSourceType(),
            physicalPlan.getPlanType(),
            routingNodeType
        ));
      }

      final String stmtLower = statement.getStatementText().toLowerCase(Locale.ROOT);
      final String messageLower = e.getMessage().toLowerCase(Locale.ROOT);
      final String stackLower = Throwables.getStackTraceAsString(e).toLowerCase(Locale.ROOT);

      // do not include the statement text in the default logs as it may contain sensitive
      // information - the exception which is returned to the user below will contain
      // the contents of the query
      if (messageLower.contains(stmtLower) || stackLower.contains(stmtLower)) {
        final StackTraceElement loc = Iterables
            .getLast(Throwables.getCausalChain(e))
            .getStackTrace()[0];
        LOG.error("Failure to execute pull query {} {}, not logging the error message since it "
            + "contains the query string, which may contain sensitive information. If you "
            + "see this LOG message, please submit a GitHub ticket and we will scrub "
            + "the statement text from the error at {}",
            routingOptions.debugString(),
            queryPlannerOptions.debugString(),
            loc);
      } else {
        LOG.error("Failure to execute pull query. {} {}",
            routingOptions.debugString(),
            queryPlannerOptions.debugString(),
            e);
      }
      LOG.debug("Failed pull query text {}, {}", statement.getStatementText(), e);

      throw new KsqlStatementException(
          e.getMessage() == null
              ? "Server Error" + Arrays.toString(e.getStackTrace())
              : e.getMessage(),
          statement.getStatementText(),
          e
      );
    }
  }

  ScalablePushQueryMetadata executeScalablePushQuery(
      final ImmutableAnalysis analysis,
      final ConfiguredStatement<Query> statement,
      final PushRouting pushRouting,
      final PushRoutingOptions pushRoutingOptions,
      final QueryPlannerOptions queryPlannerOptions,
      final Context context,
      final Optional<ScalablePushQueryMetrics> scalablePushQueryMetrics
  ) {
    final SessionConfig sessionConfig = statement.getSessionConfig();

    // If we ever change how many hops a request can do, we'll need to update this for correct
    // metrics.
    final RoutingNodeType routingNodeType = pushRoutingOptions.getHasBeenForwarded()
        ? RoutingNodeType.REMOTE_NODE : RoutingNodeType.SOURCE_NODE;

    PushPhysicalPlan plan = null;

    try {
      final KsqlConfig ksqlConfig = sessionConfig.getConfig(false);
      final LogicalPlanNode logicalPlan = buildAndValidateLogicalPlan(
          statement, analysis, ksqlConfig, queryPlannerOptions, true);
      plan = buildScalablePushPhysicalPlan(
          logicalPlan,
          analysis,
          context,
          pushRoutingOptions
      );
      final  PushPhysicalPlan physicalPlan = plan;

      final TransientQueryQueue transientQueryQueue
          = new TransientQueryQueue(analysis.getLimitClause());
      final PushQueryMetadata.ResultType resultType =
          physicalPlan.getScalablePushRegistry().isTable()
          ? physicalPlan.getScalablePushRegistry().isWindowed() ? ResultType.WINDOWED_TABLE
              : ResultType.TABLE
          : ResultType.STREAM;

      final PushQueryQueuePopulator populator = () ->
          pushRouting.handlePushQuery(serviceContext, physicalPlan, statement, pushRoutingOptions,
              physicalPlan.getOutputSchema(), transientQueryQueue, scalablePushQueryMetrics);
      final PushQueryPreparer preparer = () ->
          pushRouting.preparePushQuery(physicalPlan, statement, pushRoutingOptions);
      final ScalablePushQueryMetadata metadata = new ScalablePushQueryMetadata(
          physicalPlan.getOutputSchema(),
          physicalPlan.getQueryId(),
          transientQueryQueue,
          scalablePushQueryMetrics,
          resultType,
          populator,
          preparer,
          physicalPlan.getSourceType(),
          routingNodeType,
          physicalPlan::getRowsReadFromDataSource
      );

      return metadata;
    } catch (final Exception e) {
      if (plan == null) {
        scalablePushQueryMetrics.ifPresent(m -> m.recordErrorRateForNoResult(1));
      } else {
        final PushPhysicalPlan pushPhysicalPlan = plan;
        scalablePushQueryMetrics.ifPresent(metrics -> metrics.recordErrorRate(1,
                pushPhysicalPlan.getSourceType(),
                routingNodeType
        ));
      }

      final String stmtLower = statement.getStatementText().toLowerCase(Locale.ROOT);
      final String messageLower = e.getMessage().toLowerCase(Locale.ROOT);
      final String stackLower = Throwables.getStackTraceAsString(e).toLowerCase(Locale.ROOT);

      // do not include the statement text in the default logs as it may contain sensitive
      // information - the exception which is returned to the user below will contain
      // the contents of the query
      if (messageLower.contains(stmtLower) || stackLower.contains(stmtLower)) {
        final StackTraceElement loc = Iterables
                .getLast(Throwables.getCausalChain(e))
                .getStackTrace()[0];
        LOG.error("Failure to execute push query V2 {} {}, not logging the error message since it "
                        + "contains the query string, which may contain sensitive information."
                        + " If you see this LOG message, please submit a GitHub ticket and"
                        + " we will scrub the statement text from the error at {}",
                pushRoutingOptions.debugString(),
                queryPlannerOptions.debugString(),
                loc);
      } else {
        LOG.error("Failure to execute push query V2. {} {}",
                pushRoutingOptions.debugString(),
                queryPlannerOptions.debugString(),
                e);
      }
      LOG.debug("Failed push query V2 text {}, {}", statement.getStatementText(), e);

      throw new KsqlStatementException(
              e.getMessage() == null
                      ? "Server Error" + Arrays.toString(e.getStackTrace())
                      : e.getMessage(),
              statement.getStatementText(),
              e
      );
    }
  }


  @SuppressWarnings("OptionalGetWithoutIsPresent") // Known to be non-empty
  TransientQueryMetadata executeTransientQuery(
      final ConfiguredStatement<Query> statement,
      final boolean excludeTombstones
  ) {
    final ExecutorPlans plans = planQuery(statement, statement.getStatement(),
        Optional.empty(), Optional.empty(), engineContext.getMetaStore());
    final KsqlBareOutputNode outputNode = (KsqlBareOutputNode) plans.logicalPlan.getNode().get();
    engineContext.createQueryValidator().validateQuery(
        config,
        plans.physicalPlan,
        engineContext.getQueryRegistry().getAllLiveQueries()
    );
    return engineContext.getQueryRegistry().createTransientQuery(
        config,
        serviceContext,
        engineContext.getProcessingLogContext(),
        engineContext.getMetaStore(),
        statement.getStatementText(),
        plans.physicalPlan.getQueryId(),
        getSourceNames(outputNode),
        plans.physicalPlan.getPhysicalPlan(),
        buildPlanSummary(
            plans.physicalPlan.getQueryId(),
            plans.physicalPlan.getPhysicalPlan()),
        outputNode.getSchema(),
        outputNode.getLimit(),
        outputNode.getWindowInfo(),
        excludeTombstones
    );
  }

  @SuppressWarnings("OptionalGetWithoutIsPresent") // Known to be non-empty
  TransientQueryMetadata executeStreamPullQuery(
      final ConfiguredStatement<Query> statement,
      final boolean excludeTombstones,
      final ImmutableMap<TopicPartition, Long> endOffsets
  ) {
    final ExecutorPlans plans = planQuery(statement, statement.getStatement(),
        Optional.empty(), Optional.empty(), engineContext.getMetaStore());
    final KsqlBareOutputNode outputNode = (KsqlBareOutputNode) plans.logicalPlan.getNode().get();
    engineContext.createQueryValidator().validateQuery(
        config,
        plans.physicalPlan,
        engineContext.getQueryRegistry().getAllLiveQueries()
    );
    return engineContext.getQueryRegistry().createStreamPullQuery(
        config,
        serviceContext,
        engineContext.getProcessingLogContext(),
        engineContext.getMetaStore(),
        statement.getStatementText(),
        plans.physicalPlan.getQueryId(),
        getSourceNames(outputNode),
        plans.physicalPlan.getPhysicalPlan(),
        buildPlanSummary(
            plans.physicalPlan.getQueryId(),
            plans.physicalPlan.getPhysicalPlan()),
        outputNode.getSchema(),
        outputNode.getLimit(),
        outputNode.getWindowInfo(),
        excludeTombstones,
        endOffsets
    );
  }

  @SuppressFBWarnings(value = "NP_NULL_PARAM_DEREF_NONVIRTUAL")
  private KsqlPlan sourceTablePlan(
      final ConfiguredStatement<?> statement) {
    final CreateTable createTable = (CreateTable) statement.getStatement();
    final CreateTableCommand ddlCommand = (CreateTableCommand) engineContext.createDdlCommand(
        statement.getStatementText(),
        (ExecutableDdlStatement) statement.getStatement(),
        config
    );

    final Relation from = new AliasedRelation(
        new Table(createTable.getName()), createTable.getName());

    // Only VALUE columns must be selected from the source table. When running a pull query, the
    // keys are added if selecting all columns.
    final Select select = new Select(
        createTable.getElements().stream()
            .filter(column -> column.getNamespace() == TableElement.Namespace.VALUE)
            .map(column -> new SingleColumn(
                new UnqualifiedColumnReferenceExp(column.getName()),
                Optional.of(column.getName())))
            .collect(Collectors.toList()));

    // Source table need to keep emitting changes so every new record is materialized for
    // pull query availability.
    final RefinementInfo refinementInfo = RefinementInfo.of(OutputRefinement.CHANGES);

    // This is a plan for a `select * from <source-table> emit changes` statement,
    // without a sink topic to write the results. The query is just made to materialize the
    // source table.
    final Query query = new Query(
        Optional.empty(),
        select,
        from,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(refinementInfo),
        false,
        OptionalInt.empty());

    // The source table does not exist in the current metastore, so a temporary metastore that
    // contains only the source table is created here. This metastore is used later to create
    // ExecutorsPlan.
    final MutableMetaStore tempMetastore = new MetaStoreImpl(new InternalFunctionRegistry());
    final Formats formats = ddlCommand.getFormats();
    tempMetastore.putSource(new KsqlTable<>(
        statement.getStatementText(),
        createTable.getName(),
        ddlCommand.getSchema(),
        Optional.empty(),
        false,
        new KsqlTopic(
            ddlCommand.getTopicName(),
            KeyFormat.of(formats.getKeyFormat(), formats.getKeyFeatures(), Optional.empty()),
            ValueFormat.of(formats.getValueFormat(), formats.getValueFeatures())
        ),
        true
    ), false);

    final ExecutorPlans plans = planQuery(
        statement,
        query,
        Optional.empty(),
        Optional.empty(),
        tempMetastore
    );

    final KsqlBareOutputNode outputNode =
        (KsqlBareOutputNode) plans.logicalPlan.getNode().get();

    final QueryPlan queryPlan = new QueryPlan(
        getSourceNames(outputNode),
        Optional.empty(),
        plans.physicalPlan.getPhysicalPlan(),
        plans.physicalPlan.getQueryId(),
        getApplicationId()
    );

    engineContext.createQueryValidator().validateQuery(
        config,
        plans.physicalPlan,
        engineContext.getQueryRegistry().getAllLiveQueries()
    );

    return KsqlPlan.queryPlanCurrent(
        statement.getStatementText(),
        Optional.of(ddlCommand),
        queryPlan);
  }

  private boolean isSourceStreamOrTable(final ConfiguredStatement<?> statement) {
    return (statement.getStatement() instanceof CreateStream
        && ((CreateStream) statement.getStatement()).isSource())
        || (statement.getStatement() instanceof CreateTable
        && ((CreateTable) statement.getStatement()).isSource());
  }

  private boolean isSourceTableMaterializationEnabled() {
    // Do not get overridden configs because this must be set only from the Server side
    return config.getConfig(false)
        .getBoolean(KsqlConfig.KSQL_SOURCE_TABLE_MATERIALIZATION_ENABLED);
  }

  // Known to be non-empty
  @SuppressWarnings("OptionalGetWithoutIsPresent")
  KsqlPlan plan(final ConfiguredStatement<?> statement) {
    try {
      throwOnNonExecutableStatement(statement);

      if (statement.getStatement() instanceof ExecutableDdlStatement) {
        final boolean isSourceStream = statement.getStatement() instanceof CreateStream
            && ((CreateStream) statement.getStatement()).isSource();

        final boolean isSourceTable = statement.getStatement() instanceof CreateTable
            && ((CreateTable) statement.getStatement()).isSource();

        if ((isSourceStream || isSourceTable) && !isSourceTableMaterializationEnabled()) {
          throw new KsqlStatementException("Cannot execute command because source table "
              + "materialization is disabled.", statement.getStatementText());
        }

        if (isSourceTable) {
          return sourceTablePlan(statement);
        } else {
          final DdlCommand ddlCommand = engineContext.createDdlCommand(
              statement.getStatementText(),
              (ExecutableDdlStatement) statement.getStatement(),
              config
          );

          return KsqlPlan.ddlPlanCurrent(
              statement.getStatementText(),
              ddlCommand);
        }
      }

      final QueryContainer queryContainer = (QueryContainer) statement.getStatement();
      final ExecutorPlans plans = planQuery(
          statement,
          queryContainer.getQuery(),
          Optional.of(queryContainer.getSink()),
          queryContainer.getQueryId(),
          engineContext.getMetaStore()
      );

      final KsqlStructuredDataOutputNode outputNode =
          (KsqlStructuredDataOutputNode) plans.logicalPlan.getNode().get();

      final Optional<DdlCommand> ddlCommand = maybeCreateSinkDdl(
          statement,
          outputNode
      );

      validateResultType(outputNode.getNodeOutputType(), statement);

      final QueryPlan queryPlan = new QueryPlan(
          getSourceNames(outputNode),
          outputNode.getSinkName(),
          plans.physicalPlan.getPhysicalPlan(),
          plans.physicalPlan.getQueryId(),
          getApplicationId()
      );

      engineContext.createQueryValidator().validateQuery(
          config,
          plans.physicalPlan,
          engineContext.getQueryRegistry().getAllLiveQueries()
      );

      return KsqlPlan.queryPlanCurrent(
          statement.getStatementText(),
          ddlCommand,
          queryPlan
      );
    } catch (final KsqlStatementException e) {
      throw e;
    } catch (final Exception e) {
      throw new KsqlStatementException(e.getMessage(), statement.getStatementText(), e);
    }
  }

  private Optional<String> getApplicationId() {
    return config.getConfig(true).getBoolean(KsqlConfig.KSQL_SHARED_RUNTIME_ENABLED)
        ? Optional.of("appId")
        : Optional.empty();
  }

  private ExecutorPlans planQuery(
      final ConfiguredStatement<?> statement,
      final Query query,
      final Optional<Sink> sink,
      final Optional<String> withQueryId,
      final MetaStore metaStore) {
    final QueryEngine queryEngine = engineContext.createQueryEngine(serviceContext);
    final KsqlConfig ksqlConfig = config.getConfig(true);
    final OutputNode outputNode = QueryEngine.buildQueryLogicalPlan(
        query,
        sink,
        metaStore,
        ksqlConfig,
        getRowpartitionRowoffsetEnabled(ksqlConfig, statement.getSessionConfig().getOverrides())
    );

    final LogicalPlanNode logicalPlan = new LogicalPlanNode(
        statement.getStatementText(),
        Optional.of(outputNode)
    );

    final QueryId queryId = QueryIdUtil.buildId(
        statement.getStatement(),
        engineContext,
        engineContext.idGenerator(),
        outputNode,
        ksqlConfig.getBoolean(KsqlConfig.KSQL_CREATE_OR_REPLACE_ENABLED),
        withQueryId
    );

    if (withQueryId.isPresent()
        && engineContext.getQueryRegistry().getPersistentQuery(queryId).isPresent()) {
      throw new KsqlException(String.format("Query ID '%s' already exists.", queryId));
    }
    final Optional<PersistentQueryMetadata> persistentQueryMetadata =
        engineContext.getQueryRegistry().getPersistentQuery(queryId);

    final Optional<PlanInfo> oldPlanInfo;

    if (persistentQueryMetadata.isPresent()) {
      final ExecutionStep<?> oldPlan = persistentQueryMetadata.get().getPhysicalPlan();
      oldPlanInfo = Optional.of(oldPlan.extractPlanInfo(new PlanInfoExtractor()));
    } else {
      oldPlanInfo = Optional.empty();
    }

    final PhysicalPlan physicalPlan = queryEngine.buildPhysicalPlan(
        logicalPlan,
        config,
        metaStore,
        queryId,
        oldPlanInfo
    );
    return new ExecutorPlans(logicalPlan, physicalPlan);
  }

  private LogicalPlanNode buildAndValidateLogicalPlan(
      final ConfiguredStatement<?> statement,
      final ImmutableAnalysis analysis,
      final KsqlConfig config,
      final QueryPlannerOptions queryPlannerOptions,
      final boolean isScalablePush
  ) {
    final OutputNode outputNode = new LogicalPlanner(config, analysis, engineContext.getMetaStore())
        .buildQueryLogicalPlan(queryPlannerOptions, isScalablePush);
    return new LogicalPlanNode(
        statement.getStatementText(),
        Optional.of(outputNode)
    );
  }

  private PushPhysicalPlan buildScalablePushPhysicalPlan(
      final LogicalPlanNode logicalPlan,
      final ImmutableAnalysis analysis,
      final Context context,
      final PushRoutingOptions pushRoutingOptions
  ) {

    final PushPhysicalPlanBuilder builder = new PushPhysicalPlanBuilder(
        engineContext.getProcessingLogContext(),
        ScalablePushQueryExecutionUtil.findQuery(engineContext, analysis),
        pushRoutingOptions.getExpectingStartOfRegistryData()
    );
    return builder.buildPushPhysicalPlan(logicalPlan, context);
  }

  private PullPhysicalPlan buildPullPhysicalPlan(
      final LogicalPlanNode logicalPlan,
      final ImmutableAnalysis analysis,
      final QueryPlannerOptions queryPlannerOptions,
      final CompletableFuture<Void> shouldCancelRequests
  ) {

    final PullPhysicalPlanBuilder builder = new PullPhysicalPlanBuilder(
        engineContext.getProcessingLogContext(),
        PullQueryExecutionUtil.findMaterializingQuery(engineContext, analysis),
        analysis,
        queryPlannerOptions,
        shouldCancelRequests
    );
    return builder.buildPullPhysicalPlan(logicalPlan);
  }

  private static final class ExecutorPlans {

    private final LogicalPlanNode logicalPlan;
    private final PhysicalPlan physicalPlan;

    private ExecutorPlans(
        final LogicalPlanNode logicalPlan,
        final PhysicalPlan physicalPlan) {
      this.logicalPlan = Objects.requireNonNull(logicalPlan, "logicalPlan");
      this.physicalPlan = Objects.requireNonNull(physicalPlan, "physicalPlanNode");
    }
  }

  private Optional<DdlCommand> maybeCreateSinkDdl(
      final ConfiguredStatement<?> cfgStatement,
      final KsqlStructuredDataOutputNode outputNode
  ) {
    if (!outputNode.createInto()) {
      validateExistingSink(outputNode);
      return Optional.empty();
    }

    final Statement statement = cfgStatement.getStatement();
    final SourceName intoSource = outputNode.getSinkName().get();
    final boolean orReplace = statement instanceof CreateAsSelect
        && ((CreateAsSelect) statement).isOrReplace();
    final boolean ifNotExists = statement instanceof CreateAsSelect
        && ((CreateAsSelect) statement).isNotExists();

    final DataSource dataSource = engineContext.getMetaStore().getSource(intoSource);
    if (dataSource != null && !ifNotExists && !orReplace) {
      final String failedSourceType = outputNode.getNodeOutputType().getKsqlType();
      final String foundSourceType = dataSource.getDataSourceType().getKsqlType();

      throw new KsqlException(String.format(
          "Cannot add %s '%s': A %s with the same name already exists",
          failedSourceType.toLowerCase(), intoSource.text(), foundSourceType.toLowerCase()
      ));
    }

    return Optional.of(engineContext.createDdlCommand(outputNode));
  }

  private void validateExistingSink(
      final KsqlStructuredDataOutputNode outputNode
  ) {
    final SourceName name = outputNode.getSinkName().get();
    final DataSource existing = engineContext.getMetaStore().getSource(name);

    if (existing == null) {
      throw new KsqlException(String.format("%s does not exist.", outputNode));
    }

    if (existing.getDataSourceType() != outputNode.getNodeOutputType()) {
      throw new KsqlException(String.format(
          "Incompatible data sink and query result. Data sink"
              + " (%s) type is %s but select query result is %s.",
          name.text(),
          existing.getDataSourceType(),
          outputNode.getNodeOutputType())
      );
    }

    final LogicalSchema resultSchema = outputNode.getSchema();
    final LogicalSchema existingSchema = existing.getSchema();

    if (!resultSchema.compatibleSchema(existingSchema)) {
      throw new KsqlException("Incompatible schema between results and sink."
                                  + System.lineSeparator()
                                  + "Result schema is " + resultSchema
                                  + System.lineSeparator()
                                  + "Sink schema is " + existingSchema
      );
    }
  }

  private static void validateResultType(
      final DataSourceType dataSourceType,
      final ConfiguredStatement<?> statement
  ) {
    if (statement.getStatement() instanceof CreateStreamAsSelect
        && dataSourceType == DataSourceType.KTABLE) {
      throw new KsqlStatementException("Invalid result type. "
                                           + "Your SELECT query produces a TABLE. "
                                           + "Please use CREATE TABLE AS SELECT statement instead.",
                                       statement.getStatementText());
    }

    if (statement.getStatement() instanceof CreateTableAsSelect
        && dataSourceType == DataSourceType.KSTREAM) {
      throw new KsqlStatementException(
          "Invalid result type. Your SELECT query produces a STREAM. "
           + "Please use CREATE STREAM AS SELECT statement instead.",
          statement.getStatementText());
    }
  }

  private static void throwOnNonExecutableStatement(final ConfiguredStatement<?> statement) {
    if (!KsqlEngine.isExecutableStatement(statement.getStatement())) {
      throw new KsqlStatementException("Statement not executable", statement.getStatementText());
    }
  }

  private static Set<SourceName> getSourceNames(final PlanNode outputNode) {
    return outputNode.getSourceNodes()
        .map(DataSourceNode::getDataSource)
        .map(DataSource::getName)
        .collect(Collectors.toSet());
  }

  private String executeDdl(
      final DdlCommand ddlCommand,
      final String statementText,
      final boolean withQuery,
      final Set<SourceName> withQuerySources
  ) {
    try {
      return engineContext.executeDdl(statementText, ddlCommand, withQuery, withQuerySources);
    } catch (final KsqlStatementException e) {
      throw e;
    } catch (final Exception e) {
      throw new KsqlStatementException(e.getMessage(), statementText, e);
    }
  }

  private Set<DataSource> getSources(final QueryPlan queryPlan) {
    final ImmutableSet.Builder<DataSource> sources = ImmutableSet.builder();
    for (final SourceName name : queryPlan.getSources()) {
      final DataSource dataSource = engineContext.getMetaStore().getSource(name);
      if (dataSource == null) {
        throw new KsqlException("Unknown source: " + name.toString(FormatOptions.noEscape()));
      }

      sources.add(dataSource);
    }

    return sources.build();
  }

  private PersistentQueryMetadata executePersistentQuery(
      final QueryPlan queryPlan,
      final String statementText,
      final KsqlConstants.PersistentQueryType persistentQueryType
  ) {
    final QueryRegistry queryRegistry = engineContext.getQueryRegistry();
    return queryRegistry.createOrReplacePersistentQuery(
        config,
        serviceContext,
        engineContext.getProcessingLogContext(),
        engineContext.getMetaStore(),
        statementText,
        queryPlan.getQueryId(),
        queryPlan.getSink().map(s -> engineContext.getMetaStore().getSource(s)),
        getSources(queryPlan),
        queryPlan.getPhysicalPlan(),
        buildPlanSummary(queryPlan.getQueryId(), queryPlan.getPhysicalPlan()),
        persistentQueryType,
        queryPlan.getRuntimeId()
    );
  }

  private String buildPlanSummary(final QueryId queryId, final ExecutionStep<?> plan) {
    return new PlanSummary(queryId, config.getConfig(true), engineContext.getMetaStore())
        .summarize(plan);
  }

  private static boolean getRowpartitionRowoffsetEnabled(
      final KsqlConfig ksqlConfig,
      final Map<String, Object> configOverrides
  ) {
    final Object rowpartitionRowoffsetEnabled =
        configOverrides.get(KsqlConfig.KSQL_ROWPARTITION_ROWOFFSET_ENABLED);
    if (rowpartitionRowoffsetEnabled != null) {
      return "true".equalsIgnoreCase(rowpartitionRowoffsetEnabled.toString());
    }

    return ksqlConfig.getBoolean(KsqlConfig.KSQL_ROWPARTITION_ROWOFFSET_ENABLED);
  }
}
