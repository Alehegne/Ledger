package com.example.Ledger;

import com.example.Ledger.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LedgerApplicationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void databaseIsReachable() {
		Integer one = jdbcTemplate.queryForObject("select 1", Integer.class);
		assertThat(one).isEqualTo(1);
	}

}
