package dev.greeny.kmr.language.editor;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.unit.KmrPascalDirective;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Folds procedures and functions, var/const/type blocks, begin/end blocks, loops and with statements, records, case
 * statements, multi-line comments and {$IFDEF}...{$ENDIF} regions. A loop whose begin/end body starts on the loop's
 * own line gets no region of its own (the body's region is enough).
 */
public class KmrPascalFoldingBuilder extends FoldingBuilderEx implements DumbAware
{

	@Override
	public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement root, @NotNull Document document, boolean quick)
	{
		List<FoldingDescriptor> descriptors = new ArrayList<>();
		Deque<PsiComment> openConditionals = new ArrayDeque<>();
		for (PsiElement element : SyntaxTraverser.psiTraverser(root)) {
			if (element instanceof KmrPascalStatementList || element instanceof KmrPascalRecordType || element instanceof KmrPascalCaseStatement
				|| element instanceof KmrPascalRoutineDeclaration || element instanceof KmrPascalVarDeclarations
				|| element instanceof KmrPascalConstantDeclarations || element instanceof KmrPascalTypeDeclarations) {
				addIfMultiLine(descriptors, element, document);
			} else if (element instanceof KmrPascalForStatement || element instanceof KmrPascalWhileStatement
				|| element instanceof KmrPascalRepeatStatement || element instanceof KmrPascalWithStatement) {
				if (!bodyStartsOnSameLine(element, document)) {
					addIfMultiLine(descriptors, element, document);
				}
			} else if (element instanceof PsiComment) {
				PsiComment comment = (PsiComment) element;
				KmrPascalDirective directive = KmrPascalDirective.of(comment);
				if (directive == null) {
					addIfMultiLine(descriptors, comment, document);
				} else if (directive.kind == KmrPascalDirective.Kind.IFDEF || directive.kind == KmrPascalDirective.Kind.IFNDEF) {
					openConditionals.push(comment);
				} else if (directive.kind == KmrPascalDirective.Kind.ENDIF && !openConditionals.isEmpty()) {
					PsiComment opening = openConditionals.pop();
					TextRange range = TextRange.create(opening.getTextRange().getStartOffset(), comment.getTextRange().getEndOffset());
					if (isMultiLine(range, document)) {
						descriptors.add(new FoldingDescriptor(opening.getNode(), range, null, opening.getText() + " ... {$ENDIF}"));
					}
				}
			}
		}
		return descriptors.toArray(FoldingDescriptor.EMPTY_ARRAY);
	}

	/** {@code for ... do begin} on one line: the begin/end region already covers the loop. */
	private static boolean bodyStartsOnSameLine(@NotNull PsiElement statement, @NotNull Document document)
	{
		for (PsiElement child = statement.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof KmrPascalStatementList) {
				return document.getLineNumber(child.getTextRange().getStartOffset()) == document.getLineNumber(statement.getTextRange().getStartOffset());
			}
		}
		return false;
	}

	private static void addIfMultiLine(@NotNull List<FoldingDescriptor> descriptors, @NotNull PsiElement element, @NotNull Document document)
	{
		if (isMultiLine(element.getTextRange(), document)) {
			descriptors.add(new FoldingDescriptor(element.getNode(), element.getTextRange()));
		}
	}

	private static boolean isMultiLine(@NotNull TextRange range, @NotNull Document document)
	{
		return document.getLineNumber(range.getStartOffset()) < document.getLineNumber(Math.max(range.getStartOffset(), range.getEndOffset() - 1));
	}

	@Override
	public @Nullable String getPlaceholderText(@NotNull ASTNode node)
	{
		IElementType type = node.getElementType();
		PsiElement psi = node.getPsi();
		if (psi instanceof KmrPascalRoutineDeclaration) {
			KmrPascalRoutineDeclaration routine = (KmrPascalRoutineDeclaration) psi;
			return (routine instanceof KmrPascalFunctionDeclaration ? "function " : "procedure ") + routine.getName()
				+ (routine.getParameters().isEmpty() ? "" : "(...)") + " ...";
		}
		if (type == KmrPascalTypes.STATEMENT_LIST) {
			return "begin ... end";
		}
		if (type == KmrPascalTypes.RECORD_TYPE) {
			return "record ... end";
		}
		if (type == KmrPascalTypes.CASE_STATEMENT) {
			return "case ... end";
		}
		if (type == KmrPascalTypes.VAR_DECLARATIONS) {
			return "var ...";
		}
		if (type == KmrPascalTypes.CONSTANT_DECLARATIONS) {
			return "const ...";
		}
		if (type == KmrPascalTypes.TYPE_DECLARATIONS) {
			return "type ...";
		}
		if (type == KmrPascalTypes.FOR_STATEMENT) {
			return "for ... do ...";
		}
		if (type == KmrPascalTypes.WHILE_STATEMENT) {
			return "while ... do ...";
		}
		if (type == KmrPascalTypes.REPEAT_STATEMENT) {
			return "repeat ... until ...";
		}
		if (type == KmrPascalTypes.WITH_STATEMENT) {
			return "with ... do ...";
		}
		if (type == KmrPascalTypes.COMMENT_B) {
			return "(* ... *)";
		}
		if (type == KmrPascalTypes.COMMENT_A) {
			return node.getText().startsWith("{") ? "{ ... }" : "// ...";
		}
		return "...";
	}

	@Override
	public boolean isCollapsedByDefault(@NotNull ASTNode node)
	{
		return false;
	}

}
