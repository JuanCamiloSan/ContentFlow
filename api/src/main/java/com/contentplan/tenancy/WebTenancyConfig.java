package com.contentplan.tenancy;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Aparte de {@link TenancyConfig} a propósito: {@code @WebMvcTest} recoge todo {@code WebMvcConfigurer},
 * y mezclarlo con el administrador de transacciones le exigiría un {@code DataSource} a las pruebas de
 * la capa web.
 */
@Configuration(proxyBeanMethods = false)
class WebTenancyConfig implements WebMvcConfigurer {

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ExigeWorkspaceInterceptor());
	}

}
