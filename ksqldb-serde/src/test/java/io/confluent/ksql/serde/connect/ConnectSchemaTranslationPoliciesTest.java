/*
 * Copyright 2021 Confluent Inc.
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
package io.confluent.ksql.serde.connect;

import static io.confluent.ksql.serde.connect.ConnectSchemaTranslationPolicy.ORIGINAL_FIELD_NAME;
import static io.confluent.ksql.serde.connect.ConnectSchemaTranslationPolicy.UPPERCASE_FIELD_NAME;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConnectSchemaTranslationPoliciesTest {

  @Test(expected = IllegalArgumentException.class)
  public void shouldThrowsWithIncompatiblePolicies() {
    // When:
    ConnectSchemaTranslationPolicies.of(ORIGINAL_FIELD_NAME, UPPERCASE_FIELD_NAME);
  }

  @Test
  public void shouldReturnCorrectEnabled() {
    // Given:
    ConnectSchemaTranslationPolicies lowercasePolicies = ConnectSchemaTranslationPolicies.of(
        UPPERCASE_FIELD_NAME);

    // When: Then:
    assertTrue(lowercasePolicies.enabled(UPPERCASE_FIELD_NAME));
    assertFalse(lowercasePolicies.enabled(ORIGINAL_FIELD_NAME));
  }
}
