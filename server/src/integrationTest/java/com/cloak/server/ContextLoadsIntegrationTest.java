package com.cloak.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloak.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ContextLoadsIntegrationTest extends IntegrationTestBase {

  @Autowired JdbcTemplate jdbc;

  @Test
  void contextLoads_andFlywayAppliedBaselineSchema() {
    // The Flyway baseline (V1) must have created the message + device tables
    // in the Postgres container during context startup.
    Integer deviceTables =
        jdbc.queryForObject(
            "select count(*) from information_schema.tables"
                + " where table_schema = 'public' and table_name = 'device'",
            Integer.class);
    Integer messageTables =
        jdbc.queryForObject(
            "select count(*) from information_schema.tables"
                + " where table_schema = 'public' and table_name = 'encrypted_message'",
            Integer.class);

    assertThat(deviceTables).isEqualTo(1);
    assertThat(messageTables).isEqualTo(1);
  }
}
