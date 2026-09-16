package dev.greeny.kmr.language.flow;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Navigation over the statement structure of the PSI (which Grammar-Kit does not expose as a common interface). */
public final class KmrStatements
{

	private KmrStatements()
	{
	}

	/** Every element the grammar's {@code statement} rule produces (the empty statement excluded). */
	public static boolean isStatement(@Nullable PsiElement element)
	{
		return element instanceof KmrPascalStatementList || element instanceof KmrPascalIfStatement || element instanceof KmrPascalWhileStatement
			|| element instanceof KmrPascalRepeatStatement || element instanceof KmrPascalWithStatement || element instanceof KmrPascalForStatement
			|| element instanceof KmrPascalCaseStatement || element instanceof KmrPascalExitStatement || element instanceof KmrPascalBreakStatement
			|| element instanceof KmrPascalContinueStatement || element instanceof KmrPascalAssignment || element instanceof KmrPascalExpressionStatement;
	}

	/**
	 * Whether statements directly inside the element form a sequence: a {@code begin..end} block, the body of a
	 * {@code repeat}, or the {@code else} part of a {@code case} (whose statements are direct children of the case).
	 */
	public static boolean isBlock(@Nullable PsiElement element)
	{
		return element instanceof KmrPascalStatementList || element instanceof KmrPascalRepeatStatement || element instanceof KmrPascalCaseStatement;
	}

	/** The statements directly inside a block, in order (for a case statement: those of its else part). */
	@NotNull
	public static List<PsiElement> statementsOf(@NotNull PsiElement block)
	{
		List<PsiElement> result = new ArrayList<>();
		for (PsiElement child = block.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (isStatement(child)) {
				result.add(child);
			}
		}
		return result;
	}

	/** The statement after {@code then}. */
	@Nullable
	public static PsiElement thenBranch(@NotNull KmrPascalIfStatement statement)
	{
		return controlled(statement, 0);
	}

	/** The statement after {@code else}, or null. */
	@Nullable
	public static PsiElement elseBranch(@NotNull KmrPascalIfStatement statement)
	{
		boolean afterElse = false;
		for (PsiElement child = statement.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNode().getElementType() == KmrPascalTypes.ELSE) {
				afterElse = true;
			} else if (afterElse && isStatement(child)) {
				return child;
			}
		}
		return null;
	}

	/** The single statement controlled by a while/for/with statement or a case branch. */
	@Nullable
	public static PsiElement body(@NotNull PsiElement holder)
	{
		return controlled(holder, 0);
	}

	@Nullable
	private static PsiElement controlled(@NotNull PsiElement holder, int index)
	{
		int seen = 0;
		for (PsiElement child = holder.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (isStatement(child)) {
				if (seen == index) {
					return child;
				}
				seen++;
			}
		}
		return null;
	}

	/**
	 * Whether the statement is the single statement controlled by an if/while/for/with or a case branch (as opposed
	 * to one of a block's sequence).
	 */
	public static boolean isControlledStatement(@NotNull PsiElement statement)
	{
		PsiElement parent = statement.getParent();
		return isStatement(statement) && parent != null && !isBlock(parent);
	}

	/** The innermost statement containing the element (the element itself when it is one). */
	@Nullable
	public static PsiElement enclosingStatement(@Nullable PsiElement element)
	{
		for (PsiElement e = element; e != null && !(e instanceof KmrPascalRoutineDeclaration); e = e.getParent()) {
			if (isStatement(e)) {
				return e;
			}
		}
		return null;
	}

	/** Whether the element is (inside) the condition of a loop that re-evaluates it: {@code while}'s or {@code repeat}'s. */
	public static boolean isLoopCondition(@NotNull PsiElement element, @NotNull PsiElement loop)
	{
		KmrPascalExpression condition = loop instanceof KmrPascalWhileStatement ? ((KmrPascalWhileStatement) loop).getExpression()
			: loop instanceof KmrPascalRepeatStatement ? ((KmrPascalRepeatStatement) loop).getExpression() : null;
		return condition != null && PsiTreeUtil.isAncestor(condition, element, false);
	}

}
