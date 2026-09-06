package dev.greeny.kmr.language.psi;

import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The comment written directly above a declaration, split into a description and {@code @tag value} lines.
 * Used by the stubs ({@code @deprecated}, {@code @param}, {@code @return}, {@code @see}, {@code @hidden},
 * {@code @event}, {@code @kind}, {@code @prefer}) and by documentation / kinds for user code.
 */
public final class KmrPascalDocComment
{

	public static final String TAG_DEPRECATED = "deprecated";
	public static final String TAG_HIDDEN = "hidden";
	public static final String TAG_EVENT = "event";
	public static final String TAG_PARAM = "param";
	public static final String TAG_RETURN = "return";
	public static final String TAG_SEE = "see";
	/** {@code @kind <policy> <label>} on a stub alias type, {@code @kind <label>} / {@code @kind <param> <label>} on user declarations. */
	public static final String TAG_KIND = "kind";
	/** {@code @prefer TKMUnitType} on a legacy-id alias: the enum newer API functions take instead. */
	public static final String TAG_PREFER = "prefer";

	private final String description;
	private final Map<String, List<String>> tags;

	private KmrPascalDocComment(@NotNull String description, @NotNull Map<String, List<String>> tags)
	{
		this.description = description;
		this.tags = tags;
	}

	@NotNull
	public String getDescription()
	{
		return description;
	}

	public boolean hasTag(@NotNull String name)
	{
		return tags.containsKey(name.toLowerCase());
	}

	/** Values of all occurrences of a tag, in order. */
	@NotNull
	public List<String> getTagValues(@NotNull String name)
	{
		return tags.getOrDefault(name.toLowerCase(), Collections.emptyList());
	}

	/** First value of a tag, or null. */
	@Nullable
	public String getTagValue(@NotNull String name)
	{
		List<String> values = getTagValues(name);
		return values.isEmpty() ? null : values.get(0);
	}

	/** The doc comment of a declaration, or null if none precedes it. */
	@Nullable
	public static KmrPascalDocComment of(@NotNull PsiElement declaration)
	{
		PsiElement anchor = commentAnchor(declaration);
		List<String> texts = new ArrayList<>();
		for (PsiElement sibling = anchor.getPrevSibling(); sibling != null; sibling = sibling.getPrevSibling()) {
			if (sibling instanceof PsiWhiteSpace) {
				// a blank line separates the comment from the declaration
				if (sibling.getText().chars().filter(c -> c == '\n').count() > 1) {
					break;
				}
				continue;
			}
			if (sibling instanceof PsiComment && !KmrPascalTokenSets.DIRECTIVES.contains(sibling.getNode().getElementType())) {
				texts.add(0, stripDelimiters(sibling.getText()));
				// several consecutive "//" lines form one comment, a block comment stands alone
				if (!sibling.getText().startsWith("//")) {
					break;
				}
				continue;
			}
			break;
		}
		return texts.isEmpty() ? null : parse(String.join("\n", texts));
	}

	/** For identifiers declared in a list ({@code a, b: Integer}) the comment sits above the whole declaration. */
	@NotNull
	private static PsiElement commentAnchor(@NotNull PsiElement declaration)
	{
		PsiElement anchor = declaration;
		if (declaration instanceof KmrPascalVarIdentifier || declaration instanceof KmrPascalFieldIdentifier
			|| declaration instanceof KmrPascalParameterIdentifier) {
			anchor = declaration.getParent() == null ? declaration : declaration.getParent();
		}
		// first declaration of a var/const/type block: a comment above the keyword documents it
		PsiElement previous = anchor.getPrevSibling();
		while (previous instanceof PsiWhiteSpace) {
			previous = previous.getPrevSibling();
		}
		if (previous != null && KEYWORDS_OPENING_BLOCK.contains(previous.getNode().getElementType()) && anchor.getParent() != null) {
			return anchor.getParent();
		}
		return anchor;
	}

	private static final com.intellij.psi.tree.TokenSet KEYWORDS_OPENING_BLOCK = com.intellij.psi.tree.TokenSet.create(KmrPascalTypes.VAR, KmrPascalTypes.CONST, KmrPascalTypes.TYPE);

	@NotNull
	public static String stripDelimiters(@NotNull String comment)
	{
		if (comment.startsWith("//")) {
			return comment.substring(2);
		}
		if (comment.startsWith("(*")) {
			return comment.substring(2, comment.endsWith("*)") ? comment.length() - 2 : comment.length());
		}
		if (comment.startsWith("{")) {
			return comment.substring(1, comment.endsWith("}") ? comment.length() - 1 : comment.length());
		}
		return comment;
	}

	@NotNull
	public static KmrPascalDocComment parse(@NotNull String text)
	{
		StringBuilder description = new StringBuilder();
		Map<String, List<String>> tags = new LinkedHashMap<>();
		String currentTag = null;
		StringBuilder currentValue = new StringBuilder();
		for (String rawLine : text.split("\n")) {
			String line = rawLine.trim();
			if (line.startsWith("@") && line.length() > 1 && Character.isLetter(line.charAt(1))) {
				flush(tags, currentTag, currentValue);
				int space = line.indexOf(' ');
				currentTag = (space < 0 ? line.substring(1) : line.substring(1, space)).toLowerCase();
				currentValue = new StringBuilder(space < 0 ? "" : line.substring(space + 1).trim());
			} else if (currentTag != null) {
				if (!line.isEmpty()) {
					currentValue.append(currentValue.length() == 0 ? "" : " ").append(line);
				}
			} else {
				if (description.length() > 0) {
					description.append('\n');
				}
				description.append(line);
			}
		}
		flush(tags, currentTag, currentValue);
		return new KmrPascalDocComment(description.toString().trim(), tags);
	}

	private static void flush(Map<String, List<String>> tags, @Nullable String tag, StringBuilder value)
	{
		if (tag != null) {
			tags.computeIfAbsent(tag, k -> new ArrayList<>()).add(value.toString().trim());
		}
	}

}
