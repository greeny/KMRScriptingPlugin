package dev.greeny.kmr.language;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.KmrPascalFile;
import dev.greeny.kmr.language.psi.KmrPascalTokenSets;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexical problems the lexer cannot express as separate tokens (unterminated strings / comments / directives),
 * dimming of code the preprocessor removes in the current compilation unit, and colouring of {@code @tag} lines in
 * comments (doc tags such as {@code @since}, {@code @param}, {@code @kind}).
 */
public class KmrPascalAnnotator implements Annotator
{

	@Override
	public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder)
	{
		if (element instanceof KmrPascalFile) {
			annotateInactiveCode((KmrPascalFile) element, holder);
			return;
		}

		IElementType type = element.getNode().getElementType();
		String text = element.getText();

		if (type == KmrPascalTypes.STRING) {
			if (text.length() < 2 || !text.endsWith("'")) {
				createError(holder, "Unterminated string", element);
			}
		} else if (type == KmrPascalTypes.COMMENT_A) {
			if (text.startsWith("{") && !text.endsWith("}")) {
				createError(holder, "Unterminated comment", element);
			}
			annotateDocTags(element, holder);
		} else if (type == KmrPascalTypes.COMMENT_B) {
			if (text.length() < 4 || !text.endsWith("*)")) {
				createError(holder, "Unterminated comment", element);
			}
			annotateDocTags(element, holder);
		} else if (KmrPascalTokenSets.DIRECTIVES.contains(type)) {
			if (!text.endsWith("}")) {
				createError(holder, "Unterminated directive", element);
			}
		}
	}

	/** A comment line of the form {@code @tag value}: start and end of the tag word and end of the line (in the comment text). */
	public static final class DocTagLine
	{
		public final int tagStart;
		public final int tagEnd;
		public final int lineEnd;

		DocTagLine(int tagStart, int tagEnd, int lineEnd)
		{
			this.tagStart = tagStart;
			this.tagEnd = tagEnd;
			this.lineEnd = lineEnd;
		}
	}

	/** The {@code @tag} lines of a comment's text: the tag must be the first thing on its line (after {@code //}, {@code {} or spaces). */
	@NotNull
	public static List<DocTagLine> docTagLines(@NotNull String comment)
	{
		List<DocTagLine> result = new ArrayList<>();
		int lineStart = 0;
		while (lineStart < comment.length()) {
			int lineEnd = comment.indexOf('\n', lineStart);
			if (lineEnd < 0) {
				lineEnd = comment.length();
			}
			int i = lineStart;
			while (i < lineEnd && (Character.isWhitespace(comment.charAt(i)) || comment.charAt(i) == '{' || comment.charAt(i) == '(' || comment.charAt(i) == '*' || comment.charAt(i) == '/')) {
				i++;
			}
			if (i < lineEnd && comment.charAt(i) == '@' && i + 1 < lineEnd && Character.isLetter(comment.charAt(i + 1))) {
				int tagEnd = i + 1;
				while (tagEnd < lineEnd && (Character.isLetterOrDigit(comment.charAt(tagEnd)) || comment.charAt(tagEnd) == '-')) {
					tagEnd++;
				}
				int valueEnd = lineEnd;
				while (valueEnd > tagEnd && (Character.isWhitespace(comment.charAt(valueEnd - 1)) || comment.charAt(valueEnd - 1) == '}' || comment.charAt(valueEnd - 1) == ')' || comment.charAt(valueEnd - 1) == '*')) {
					valueEnd--;
				}
				result.add(new DocTagLine(i, tagEnd, Math.max(tagEnd, valueEnd)));
			}
			lineStart = lineEnd + 1;
		}
		return result;
	}

	private static void annotateDocTags(@NotNull PsiElement comment, @NotNull AnnotationHolder holder)
	{
		int offset = comment.getTextRange().getStartOffset();
		for (DocTagLine line : docTagLines(comment.getText())) {
			holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(TextRange.create(offset + line.tagStart, offset + line.tagEnd))
				.textAttributes(KmrPascalSyntaxHighlighter.DOC_TAG).create();
			if (line.lineEnd > line.tagEnd) {
				holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(TextRange.create(offset + line.tagEnd, offset + line.lineEnd))
					.textAttributes(KmrPascalSyntaxHighlighter.DOC_TAG_VALUE).create();
			}
		}
	}

	private void annotateInactiveCode(@NotNull KmrPascalFile file, @NotNull AnnotationHolder holder)
	{
		KmrPascalCompilationUnit unit = KmrPascalResolveContextService.getInstance(file.getProject()).getUnitFor(file);
		for (TextRange range : unit.inactiveRanges(file)) {
			if (!range.isEmpty()) {
				holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
					.range(range)
					.textAttributes(KmrPascalSyntaxHighlighter.INACTIVE_CODE)
					.create();
			}
		}
	}

	private void createError(@NotNull AnnotationHolder holder, String message, PsiElement element)
	{
		int end = element.getTextRange().getEndOffset();
		holder.newAnnotation(HighlightSeverity.ERROR, message)
			.tooltip(message)
			.range(TextRange.create(end, end))
			.afterEndOfLine()
			.create();
	}

}
