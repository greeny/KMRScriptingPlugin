package dev.greeny.kmr.language.types;

import com.intellij.psi.PsiElement;
import dev.greeny.kmr.language.psi.KmrPascalDocComment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A "kind": what an integer value means for the game (unit ID, hand index, X coordinate, ...). Kinds ride along on
 * {@link KmrType#INTEGER} values as {@link KmrType#valueKind} and never change the coarse type checks of {@link KmrType}.
 * <p>
 * The API stubs declare kinds as hidden alias types ({@code TKMUnitID = Integer} with {@code @kind id unit ID});
 * user code gets kinds from event handler signatures, an explicit {@code @kind} tag, or flow inference
 * ({@link KmrKindInference}).
 */
public final class KmrKind
{

	/** Rule set of a kind (the value of the {@code @kind} tag). */
	public enum Policy
	{
		/** unit/house/group id: no arithmetic, no literals except in comparisons and negative sentinels */
		ID("id", false, false),
		/** hand/player index: literals and arithmetic fine, mismatch warns */
		INDEX("index", true, true),
		/** X / Y are two distinct kinds; literals and arithmetic fine */
		COORDINATE("coordinate", true, true),
		/** integer unit/house/ware type id of the old API: literals fine, no arithmetic, an enum type is preferred */
		LEGACY_ID("legacy-id", true, false),
		/** ticks / amounts: everything fine, mismatch warns */
		COUNT("count", true, true),
		/** user label without a policy: only mismatches are reported */
		LABEL("label", true, true);

		public final String tag;
		public final boolean allowsLiterals;
		public final boolean allowsArithmetic;

		Policy(String tag, boolean allowsLiterals, boolean allowsArithmetic)
		{
			this.tag = tag;
			this.allowsLiterals = allowsLiterals;
			this.allowsArithmetic = allowsArithmetic;
		}

		@Nullable
		static Policy byTag(@NotNull String tag)
		{
			for (Policy policy : values()) {
				if (policy.tag.equalsIgnoreCase(tag)) {
					return policy;
				}
			}
			return null;
		}
	}

	/** Alias type name in the stubs ({@code TKMUnitID}) or the user's label; kinds are equal when names match. */
	public final String name;
	public final Policy policy;
	/** Words shown to the user: "unit ID". */
	public final String label;
	/** For legacy ids: the enum type newer API functions use instead ({@code @prefer TKMUnitType}). */
	@Nullable
	public final String preferredType;
	/** The alternatives of a union kind ({@code @kind aId unitId|houseId}); a plain kind lists itself. */
	public final List<KmrKind> members;
	/** Literals allowed (all members of a union must allow them). */
	public final boolean allowsLiterals;
	/** Arithmetic allowed (all members of a union must allow it). */
	public final boolean allowsArithmetic;

	private KmrKind(@NotNull String name, @NotNull Policy policy, @NotNull String label, @Nullable String preferredType)
	{
		this.name = name;
		this.policy = policy;
		this.label = label;
		this.preferredType = preferredType;
		this.members = List.of(this);
		this.allowsLiterals = policy.allowsLiterals;
		this.allowsArithmetic = policy.allowsArithmetic;
	}

	private KmrKind(@NotNull List<KmrKind> members)
	{
		this.members = List.copyOf(members);
		this.name = members.stream().map(k -> k.name).collect(Collectors.joining("|"));
		this.label = members.stream().map(k -> k.label).collect(Collectors.joining(" or "));
		Policy common = members.get(0).policy;
		this.policy = members.stream().allMatch(k -> k.policy == common) ? common : Policy.LABEL;
		this.preferredType = null;
		this.allowsLiterals = members.stream().allMatch(k -> k.allowsLiterals);
		this.allowsArithmetic = members.stream().allMatch(k -> k.allowsArithmetic);
	}

	/** A value that may be any of the given kinds; one member is the member itself. */
	@NotNull
	public static KmrKind union(@NotNull List<KmrKind> members)
	{
		List<KmrKind> flat = new ArrayList<>();
		for (KmrKind member : members) {
			for (KmrKind part : member.members) {
				if (flat.stream().noneMatch(part::sameAs)) {
					flat.add(part);
				}
			}
		}
		return flat.size() == 1 ? flat.get(0) : new KmrKind(flat);
	}

	/** An ad-hoc kind from a user's {@code @kind someLabel} tag: only mismatches are reported. */
	@NotNull
	public static KmrKind label(@NotNull String label)
	{
		return new KmrKind(label, Policy.LABEL, label, null);
	}

	/**
	 * The kind declared by an alias type declaration's doc comment ({@code @kind <policy> <label words>},
	 * optionally {@code @prefer TEnum}), or null when the declaration declares no kind.
	 */
	@Nullable
	public static KmrKind fromDeclaration(@NotNull PsiElement typeDeclaration, @NotNull String typeName)
	{
		KmrPascalDocComment doc = KmrPascalDocComment.of(typeDeclaration);
		String value = doc == null ? null : doc.getTagValue(KmrPascalDocComment.TAG_KIND);
		if (value == null || value.isEmpty()) {
			return null;
		}
		int space = value.indexOf(' ');
		String policyTag = space < 0 ? value : value.substring(0, space);
		Policy policy = Policy.byTag(policyTag);
		if (policy == null) {
			// "@kind unitId" on a user alias: a reference to a known kind, or a plain label
			return null;
		}
		String label = space < 0 ? humanize(typeName) : value.substring(space + 1).trim();
		return new KmrKind(typeName, policy, label, doc.getTagValue(KmrPascalDocComment.TAG_PREFER));
	}

	/** "TKMUnitID" -> "unit ID" when an alias has no label words. */
	@NotNull
	static String humanize(@NotNull String typeName)
	{
		String name = typeName.startsWith("TKM") ? typeName.substring(3) : typeName.startsWith("T") ? typeName.substring(1) : typeName;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (i > 0 && Character.isUpperCase(c) && (Character.isLowerCase(name.charAt(i - 1)) || i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1)))) {
				sb.append(' ');
			}
			sb.append(c);
		}
		String result = sb.toString();
		return result.toUpperCase(Locale.ROOT).equals(result) ? result : result.substring(0, 1).toLowerCase(Locale.ROOT) + result.substring(1);
	}

	public boolean sameAs(@Nullable KmrKind other)
	{
		return other != null && name.equalsIgnoreCase(other.name);
	}

	/** Whether a value of this kind may be used where the other is expected: they share at least one alternative. */
	public boolean compatible(@Nullable KmrKind other)
	{
		if (other == null) {
			return false;
		}
		for (KmrKind mine : members) {
			for (KmrKind theirs : other.members) {
				if (mine.sameAs(theirs)) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean isUnion()
	{
		return members.size() > 1;
	}

	public boolean isCoordinate()
	{
		return !isUnion() && policy == Policy.COORDINATE;
	}

	/** "a unit ID", "an X coordinate": for messages. */
	@NotNull
	public String withArticle()
	{
		char first = label.isEmpty() ? 'x' : Character.toLowerCase(label.charAt(0));
		// "u" is left out on purpose: "a unit ID"; "an X coordinate" is spelled out
		boolean vowelSound = first == 'a' || first == 'e' || first == 'i' || first == 'o' || first == 'x' && label.length() > 1 && label.charAt(1) == ' ';
		return (vowelSound ? "an " : "a ") + label;
	}

	@Override
	public String toString()
	{
		return label;
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof KmrKind && sameAs((KmrKind) o);
	}

	@Override
	public int hashCode()
	{
		return name.toLowerCase(Locale.ROOT).hashCode();
	}

}
