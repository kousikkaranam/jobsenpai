package dev.kousik.jobhunt.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Points at a .tex file in the repo. The engine never parses LaTeX — it only
 * records which variant a job should use and which file was actually sent.
 *
 * At most one row may have is_default = true; that is a partial unique index in
 * the migration, not something this class enforces.
 */
@Entity
@Table(name = "resume_variant")
public class ResumeVariant extends Timestamped {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "name", nullable = false, unique = true)
	private String name;

	@Column(name = "target_role")
	private String targetRole;

	@Column(name = "tex_path", nullable = false)
	private String texPath;

	@Column(name = "is_default", nullable = false)
	private boolean isDefault;

	protected ResumeVariant() {
	}

	public ResumeVariant(String name, String texPath) {
		this.name = name;
		this.texPath = texPath;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTargetRole() {
		return targetRole;
	}

	public void setTargetRole(String targetRole) {
		this.targetRole = targetRole;
	}

	public String getTexPath() {
		return texPath;
	}

	public void setTexPath(String texPath) {
		this.texPath = texPath;
	}

	public boolean isDefault() {
		return isDefault;
	}

	public void setDefault(boolean isDefault) {
		this.isDefault = isDefault;
	}

}
