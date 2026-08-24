package dev.kousik.jobhunt.resume;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.kousik.jobhunt.api.dto.VariantRequest;
import dev.kousik.jobhunt.api.dto.VariantResponse;
import dev.kousik.jobhunt.domain.ResumeVariant;
import dev.kousik.jobhunt.repo.ResumeVariantRepository;
import dev.kousik.jobhunt.support.ConflictException;
import dev.kousik.jobhunt.support.NotFoundException;

/**
 * The resume variants a job can be matched to.
 *
 * A row is a pointer to a .tex file, not its contents. Phase 4 has Claude Code
 * edit those files directly and review the change as a git diff, which is the
 * whole reason the LaTeX never enters the database. See docs/DECISIONS.md #12.
 */
@Service
public class ResumeVariantService {

	private final ResumeVariantRepository variants;

	public ResumeVariantService(ResumeVariantRepository variants) {
		this.variants = variants;
	}

	@Transactional(readOnly = true)
	public List<VariantResponse> list() {
		return variants.findAllByOrderByNameAsc().stream().map(VariantResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public VariantResponse get(Long id) {
		return VariantResponse.from(require(id));
	}

	@Transactional
	public VariantResponse create(VariantRequest request) {
		variants.findByName(request.name().strip()).ifPresent(existing -> {
			throw new ConflictException("a variant named " + existing.getName() + " already exists");
		});

		ResumeVariant variant = new ResumeVariant(request.name().strip(), request.texPath().strip());
		variant.setTargetRole(request.targetRole());
		applyDefaultFlag(variant, request.isDefault());
		return VariantResponse.from(variants.save(variant));
	}

	@Transactional
	public VariantResponse update(Long id, VariantRequest request) {
		ResumeVariant variant = require(id);

		if (variants.existsByNameAndIdNot(request.name().strip(), id)) {
			throw new ConflictException("a variant named " + request.name().strip() + " already exists");
		}

		variant.setName(request.name().strip());
		variant.setTexPath(request.texPath().strip());
		variant.setTargetRole(request.targetRole());
		applyDefaultFlag(variant, request.isDefault());
		return VariantResponse.from(variant);
	}

	@Transactional
	public void delete(Long id) {
		// job_match.recommended_variant_id and application.resume_variant_id are
		// both ON DELETE SET NULL, so removing a variant loses the record of
		// which one was recommended but keeps the application itself.
		variants.delete(require(id));
	}

	/**
	 * resume_variant has a partial unique index allowing one default. Clearing
	 * the incumbent here means promoting a variant is one call rather than two,
	 * and there is no window in which the index is violated.
	 */
	private void applyDefaultFlag(ResumeVariant variant, Boolean requested) {
		if (!Boolean.TRUE.equals(requested)) {
			if (requested != null) {
				variant.setDefault(false);
			}
			return;
		}
		variants.findByIsDefaultTrue()
				.filter(incumbent -> !incumbent.equals(variant))
				.ifPresent(incumbent -> incumbent.setDefault(false));
		// Flush the demotion before the promotion, or both rows are momentarily
		// true and the partial unique index rejects the statement.
		variants.flush();
		variant.setDefault(true);
	}

	private ResumeVariant require(Long id) {
		return variants.findById(id).orElseThrow(() -> NotFoundException.of("resume variant", id));
	}

}
