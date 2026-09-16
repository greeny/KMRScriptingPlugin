package dev.greeny.kmr.language.refactoring;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.flow.KmrStatements;
import dev.greeny.kmr.language.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The edit behind "Introduce Variable": declares a local, assigns the expression to it right before the statement
 * that first needs it (wrapping a one-liner branch into {@code begin..end} when the statement is one), and replaces
 * the occurrences with the variable. Text-based so that the surrounding formatting is kept as it is.
 */
public final class KmrPascalIntroduceVariable
{

	/** What the edit produced (valid after the document is committed). */
	public static final class Result
	{
		public final KmrPascalVarIdentifier variable;
		public final List<KmrPascalIdentifierExpression> references;

		Result(@NotNull KmrPascalVarIdentifier variable, @NotNull List<KmrPascalIdentifierExpression> references)
		{
			this.variable = variable;
			this.references = references;
		}
	}

	/** Where the assignment goes. */
	static final class Anchor
	{
		/** The statement the assignment precedes, or the one-liner it wraps, or the repeat whose body it ends. */
		final PsiElement statement;
		final boolean wrap;
		final boolean endOfRepeatBody;

		Anchor(@NotNull PsiElement statement, boolean wrap, boolean endOfRepeatBody)
		{
			this.statement = statement;
			this.wrap = wrap;
			this.endOfRepeatBody = endOfRepeatBody;
		}
	}

	private KmrPascalIntroduceVariable()
	{
	}

	/**
	 * Performs the edit; must run inside a write action. {@code name} null picks a free name from the expression.
	 * Returns null when the expression is not inside a routine body.
	 */
	@Nullable
	public static Result introduce(@NotNull Project project, @NotNull KmrPascalExpression expression, @NotNull List<KmrPascalExpression> occurrences, @Nullable String name)
	{
		KmrPascalRoutineDeclaration routine = PsiTreeUtil.getParentOfType(expression, KmrPascalRoutineDeclaration.class);
		PsiFile file = expression.getContainingFile();
		Document document = PsiDocumentManager.getInstance(project).getDocument(file);
		if (routine == null || document == null || routine.getStatementList() == null) {
			return null;
		}
		List<KmrPascalExpression> targets = new ArrayList<>(occurrences.isEmpty() ? List.of(expression) : occurrences);
		targets.sort(Comparator.comparingInt(PsiElement::getTextOffset));
		Anchor anchor = findAnchor(targets);
		if (anchor == null) {
			return null;
		}
		String variable = name != null ? name : KmrPascalRefactoringUtil.uniqueName(KmrPascalRefactoringUtil.suggestVariableName(expression), expression);
		String type = KmrPascalRefactoringUtil.typeText(expression);
		String unit = KmrPascalRefactoringUtil.indentUnit(routine, document);
		String assignment = variable + " := " + expression.getText() + ";";

		RangeMarker anchorMarker = document.createRangeMarker(anchor.statement.getTextRange());
		String holderIndent = anchor.wrap ? KmrPascalRefactoringUtil.indentAt(document, anchor.statement.getParent().getTextRange().getStartOffset()) : "";
		String bodyIndent = anchor.endOfRepeatBody ? repeatBodyIndent((KmrPascalRepeatStatement) anchor.statement, document, unit) : "";
		DeclarationPoint declarationPoint = declarationInsertionPoint(routine, document);
		int untilOffset = anchor.endOfRepeatBody ? untilOffset((KmrPascalRepeatStatement) anchor.statement) : -1;
		RangeMarker untilMarker = untilOffset < 0 ? null : document.createRangeMarker(untilOffset, untilOffset);

		// 1. the occurrences, from the end so earlier offsets stay valid
		for (int i = targets.size() - 1; i >= 0; i--) {
			TextRange range = targets.get(i).getTextRange();
			document.replaceString(range.getStartOffset(), range.getEndOffset(), variable);
		}

		// 2. the assignment
		if (untilMarker != null) {
			int offset = untilMarker.getStartOffset();
			if (KmrPascalRefactoringUtil.isFirstOnLine(document, offset)) {
				int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
				document.insertString(lineStart, bodyIndent + assignment + "\n");
			} else {
				document.insertString(offset, assignment + " ");
			}
		} else if (anchor.wrap) {
			int start = anchorMarker.getStartOffset();
			int end = anchorMarker.getEndOffset();
			String statement = document.getText(TextRange.create(start, end));
			if (KmrPascalRefactoringUtil.isFirstOnLine(document, start)) {
				// the branch stands on its own line: begin/end take the indentation of the controlling statement
				String inner = KmrPascalRefactoringUtil.indentAt(document, start);
				int lineStart = document.getLineStartOffset(document.getLineNumber(start));
				String wrapped = holderIndent + "begin\n" + inner + assignment + "\n" + inner + statement + ";\n" + holderIndent + "end";
				document.replaceString(lineStart, end, wrapped);
			} else {
				String inner = holderIndent + unit;
				String wrapped = "begin\n" + inner + assignment + "\n" + inner + statement + ";\n" + holderIndent + "end";
				document.replaceString(start, end, wrapped);
			}
		} else {
			int start = anchorMarker.getStartOffset();
			String indent = KmrPascalRefactoringUtil.indentAt(document, start);
			document.insertString(start, KmrPascalRefactoringUtil.isFirstOnLine(document, start) ? assignment + "\n" + indent : assignment + " ");
		}

		// 3. the declaration
		int declarationOffset = declarationPoint.marker.getStartOffset();
		String declaration = variable + ": " + type + ";";
		if (declarationPoint.newBlock) {
			document.insertString(declarationOffset, declarationPoint.ownLine
				? "var\n" + declarationPoint.indent + unit + declaration + "\n" + declarationPoint.indent : "var " + declaration + " ");
		} else {
			document.insertString(declarationOffset, declarationPoint.ownLine ? "\n" + declarationPoint.indent + declaration : " " + declaration);
		}
		PsiDocumentManager.getInstance(project).commitDocument(document);

		// locate what we created
		KmrPascalRoutineDeclaration updated = PsiTreeUtil.getParentOfType(file.findElementAt(declarationPoint.marker.getStartOffset()), KmrPascalRoutineDeclaration.class, false);
		if (updated == null) {
			updated = routine.isValid() ? routine : null;
		}
		KmrPascalVarIdentifier created = null;
		List<KmrPascalIdentifierExpression> references = new ArrayList<>();
		if (updated != null) {
			for (KmrPascalVarDeclarations block : PsiTreeUtil.getChildrenOfTypeAsList(updated, KmrPascalVarDeclarations.class)) {
				for (KmrPascalVarDeclaration group : block.getVarDeclarationList()) {
					for (KmrPascalVarIdentifier identifier : group.getVarIdentifierList()) {
						if (variable.equalsIgnoreCase(identifier.getName())) {
							created = identifier;
						}
					}
				}
			}
			for (KmrPascalIdentifierExpression reference : PsiTreeUtil.findChildrenOfType(updated.getStatementList(), KmrPascalIdentifierExpression.class)) {
				if (variable.equalsIgnoreCase(reference.getReferenceName())) {
					references.add(reference);
				}
			}
		}
		return created == null ? null : new Result(created, references);
	}

	/** Where a new local declaration goes, and how the surrounding text is laid out. */
	private static final class DeclarationPoint
	{
		final RangeMarker marker;
		/** No var block yet: {@code var} itself has to be written (before {@code begin}). */
		final boolean newBlock;
		/** The declarations (or {@code begin}) stand on their own lines with this indentation. */
		final boolean ownLine;
		final String indent;

		DeclarationPoint(@NotNull RangeMarker marker, boolean newBlock, boolean ownLine, @NotNull String indent)
		{
			this.marker = marker;
			this.newBlock = newBlock;
			this.ownLine = ownLine;
			this.indent = indent;
		}
	}

	/** After the last local var declaration, or before {@code begin} when the routine has no var block. */
	@NotNull
	private static DeclarationPoint declarationInsertionPoint(@NotNull KmrPascalRoutineDeclaration routine, @NotNull Document document)
	{
		List<KmrPascalVarDeclarations> blocks = PsiTreeUtil.getChildrenOfTypeAsList(routine, KmrPascalVarDeclarations.class);
		if (blocks.isEmpty()) {
			int begin = routine.getStatementList().getTextRange().getStartOffset();
			return new DeclarationPoint(document.createRangeMarker(begin, begin), true, KmrPascalRefactoringUtil.isFirstOnLine(document, begin),
				KmrPascalRefactoringUtil.indentAt(document, begin));
		}
		KmrPascalVarDeclarations block = blocks.get(blocks.size() - 1);
		List<KmrPascalVarDeclaration> declarations = block.getVarDeclarationList();
		PsiElement last = declarations.isEmpty() ? block : declarations.get(declarations.size() - 1);
		int start = last.getTextRange().getStartOffset();
		int end = last.getTextRange().getEndOffset();
		return new DeclarationPoint(document.createRangeMarker(end, end), false, KmrPascalRefactoringUtil.isFirstOnLine(document, start),
			KmrPascalRefactoringUtil.indentAt(document, start));
	}

	/** The indentation of the repeat's body statements (one level deeper than {@code repeat} when there are none). */
	@NotNull
	private static String repeatBodyIndent(@NotNull KmrPascalRepeatStatement repeat, @NotNull Document document, @NotNull String unit)
	{
		List<PsiElement> statements = KmrStatements.statementsOf(repeat);
		if (!statements.isEmpty()) {
			return KmrPascalRefactoringUtil.indentAt(document, statements.get(statements.size() - 1).getTextRange().getStartOffset());
		}
		return KmrPascalRefactoringUtil.indentAt(document, repeat.getTextRange().getStartOffset()) + unit;
	}

	private static int untilOffset(@NotNull KmrPascalRepeatStatement repeat)
	{
		com.intellij.lang.ASTNode until = repeat.getNode().findChildByType(KmrPascalTypes.UNTIL);
		return until == null ? -1 : until.getStartOffset();
	}

	/**
	 * The statement before which a value shared by all occurrences must be computed: the innermost statement containing
	 * them all when it sits in a block; a one-liner containing them all gets wrapped; a repeat whose condition holds
	 * them all computes it at the end of its body.
	 */
	@Nullable
	static Anchor findAnchor(@NotNull List<KmrPascalExpression> occurrences)
	{
		PsiElement common = PsiTreeUtil.findCommonParent(occurrences);
		PsiElement statement = KmrStatements.enclosingStatement(common);
		if (statement == null) {
			return null;
		}
		if (KmrStatements.isBlock(statement)) {
			// all occurrences inside a block's statements: the first statement holding one is the anchor
			PsiElement first = null;
			for (PsiElement child : KmrStatements.statementsOf(statement)) {
				if (PsiTreeUtil.isAncestor(child, occurrences.get(0), false)) {
					first = child;
				}
			}
			if (first != null) {
				return new Anchor(first, false, false);
			}
			if (statement instanceof KmrPascalRepeatStatement && KmrStatements.isLoopCondition(common, statement)) {
				return new Anchor(statement, false, true);
			}
		}
		PsiElement parent = statement.getParent();
		if (KmrStatements.isBlock(parent) || parent instanceof KmrPascalRoutineDeclaration) {
			return new Anchor(statement, false, false);
		}
		return new Anchor(statement, true, false); // the controlled statement of an if/while/for/with/case branch
	}

}
