package com.contentplan.identity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.contentplan.tenancy.CualquierMiembro;

/**
 * El buzón de la aplicación (C6): listar y marcar como leído. Los cuatro roles tienen buzón. No hay
 * endpoint para crear avisos: los escribe el sistema por {@link Notificador}.
 */
@RestController
@RequestMapping("/notificaciones")
@CualquierMiembro
class NotificacionesController {

	static final int LIMITE_POR_DEFECTO = 50;

	static final int LIMITE_MAXIMO = 100;

	private final Buzon buzon;

	NotificacionesController(Buzon buzon) {
		this.buzon = buzon;
	}

	/** @param filtro {@code todas} (por defecto), {@code sin-leer} o {@code importantes} */
	@GetMapping
	List<Notificacion> listar(@RequestParam(defaultValue = "todas") String filtro,
			@RequestParam(defaultValue = "" + LIMITE_POR_DEFECTO) int limite) {
		if (limite < 1 || limite > LIMITE_MAXIMO) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El límite va de 1 a " + LIMITE_MAXIMO);
		}
		return buzon.listar(filtroDe(filtro), limite);
	}

	/** Para el contador del menú. */
	@GetMapping("/sin-leer/cantidad")
	Map<String, Long> cantidadSinLeer() {
		return Map.of("cantidad", buzon.contarSinLeer());
	}

	@PostMapping("/{id}/leer")
	ResponseEntity<Void> leer(@PathVariable UUID id) {
		return buzon.marcarLeida(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	@PostMapping("/leer-todas")
	Map<String, Integer> leerTodas() {
		return Map.of("marcadas", buzon.marcarTodasLeidas());
	}

	private static Buzon.Filtro filtroDe(String valor) {
		return switch (valor) {
			case "todas" -> Buzon.Filtro.TODAS;
			case "sin-leer" -> Buzon.Filtro.SIN_LEER;
			case "importantes" -> Buzon.Filtro.IMPORTANTES;
			// Sin eco del valor recibido
			default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Filtro inválido: use todas, sin-leer o importantes");
		};
	}

}
