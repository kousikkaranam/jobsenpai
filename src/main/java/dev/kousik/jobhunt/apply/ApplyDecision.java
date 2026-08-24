package dev.kousik.jobhunt.apply;

import java.util.List;

/**
 * Whether one job may be applied to unattended, and if not, why not.
 *
 * The reasons are kept as text rather than an enum because they are read by a
 * person deciding whether the guard is being too strict, and "expected salary
 * not stated in applicant.json" is more useful than MISSING_FIELD.
 */
public record ApplyDecision(boolean allowed, List<String> reasons) {

	public ApplyDecision {
		reasons = reasons == null ? List.of() : List.copyOf(reasons);
	}

	public static ApplyDecision allow() {
		return new ApplyDecision(true, List.of());
	}

	public static ApplyDecision blocked(String... reasons) {
		return new ApplyDecision(false, List.of(reasons));
	}

	public static ApplyDecision blocked(List<String> reasons) {
		return new ApplyDecision(false, reasons);
	}

	public String summary() {
		return allowed ? "eligible" : String.join("; ", reasons);
	}

}
