package dev.kousik.jobhunt.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.kousik.jobhunt.api.dto.BulkSourceRequest;
import dev.kousik.jobhunt.api.dto.SourceResponse;
import dev.kousik.jobhunt.source.BoardDiscovery;
import dev.kousik.jobhunt.source.SourceService;
import dev.kousik.jobhunt.source.SourceSweepService;
import dev.kousik.jobhunt.source.SweepReport;

import jakarta.validation.Valid;

/**
 * The company watchlist and the sweep that runs over it.
 *
 * Sweeping is a POST because it is emphatically not idempotent-and-free: it
 * makes one outbound request per company against somebody else's public API.
 */
@RestController
@RequestMapping("/api/sources")
public class SourceController {

	private final SourceService sources;

	private final SourceSweepService sweep;

	private final BoardDiscovery discovery;

	public SourceController(SourceService sources, SourceSweepService sweep, BoardDiscovery discovery) {
		this.sources = sources;
		this.sweep = sweep;
		this.discovery = discovery;
	}

	@GetMapping
	public List<SourceResponse> list() {
		return sources.list().stream().map(SourceResponse::from).toList();
	}

	@PostMapping("/bulk")
	@ResponseStatus(HttpStatus.CREATED)
	public SourceService.BulkResult addBulk(@Valid @RequestBody BulkSourceRequest request) {
		return sources.addBulk(request.text());
	}

	@PostMapping("/{id}/enabled")
	public SourceResponse setEnabled(@PathVariable Long id, @RequestParam boolean value) {
		return SourceResponse.from(sources.setEnabled(id, value));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		sources.delete(id);
	}

	/**
	 * Fetch from every enabled board, filter on the configured target roles, and
	 * ingest what is left. Returns what happened per company, including the
	 * boards that could not be read -- a wrong token silently returning nothing
	 * for weeks is the failure this reports against.
	 */
	@PostMapping("/sweep")
	public SweepReport sweep() {
		return sweep.sweepAll();
	}

	/**
	 * Find boards rather than being told about them.
	 *
	 * With no body, probes the shipped candidate list. With a body, probes the
	 * names given -- which is how a company someone actually wants gets added
	 * without them having to know whether it runs Greenhouse, Lever or Ashby,
	 * or what its board token is spelled like.
	 */
	@PostMapping("/discover")
	public BoardDiscovery.Result discover(@RequestBody(required = false) DiscoverRequest request) {
		return request == null || request.names() == null || request.names().isBlank()
				? discovery.discoverSeeded()
				: discovery.discover(List.of(request.names().split("\\R|,")));
	}

	/** @param names one company per line, or comma-separated */
	public record DiscoverRequest(String names) {
	}

}
