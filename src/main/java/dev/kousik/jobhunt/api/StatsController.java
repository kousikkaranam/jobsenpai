package dev.kousik.jobhunt.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.api.dto.StatsResponse;
import dev.kousik.jobhunt.query.StatsService;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

	private final StatsService stats;

	public StatsController(StatsService stats) {
		this.stats = stats;
	}

	@GetMapping
	public StatsResponse get() {
		return stats.current();
	}

}
