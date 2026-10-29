package com.contentflow.tenancy;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class TenancyConfig {

	/** Reemplaza al administrador de transacciones por defecto de Spring Boot. */
	@Bean
	PlatformTransactionManager transactionManager(DataSource dataSource) {
		return new TransaccionesConContexto(dataSource);
	}

}
