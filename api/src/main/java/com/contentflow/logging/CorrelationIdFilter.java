package com.contentflow.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Asigna un identificador de correlación a cada petición y lo deja en el MDC, de modo que toda
 * línea de log escrita durante la petición lo lleve. Si el cliente manda uno válido en la cabecera
 * se reutiliza; si no, o si trae caracteres raros, se genera uno nuevo (evita inyectar saltos de
 * línea o texto arbitrario en los logs).
 *
 * La línea de cierre registra solo método, estado y duración. No incluye ruta, query ni cabeceras:
 * pueden llevar tokens, identificadores de clientes o rutas de archivos, que no se registran.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Correlation-Id";
	static final String MDC_KEY = "correlationId";

	private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String id = identificadorPara(request.getHeader(HEADER));
		MDC.put(MDC_KEY, id);
		response.setHeader(HEADER, id);
		long inicio = System.nanoTime();
		try {
			chain.doFilter(request, response);
		}
		finally {
			log.info("Petición atendida method={} status={} durationMs={}", request.getMethod(),
					response.getStatus(), (System.nanoTime() - inicio) / 1_000_000);
			MDC.remove(MDC_KEY);
		}
	}

	private static String identificadorPara(String recibido) {
		if (recibido != null && VALIDO.matcher(recibido).matches()) {
			return recibido;
		}
		return UUID.randomUUID().toString();
	}

}
