package dev.greeny.kmr.language.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import dev.greeny.kmr.language.psi.KmrPascalExpression;
import dev.greeny.kmr.language.psi.KmrPascalReference;
import dev.greeny.kmr.language.psi.KmrPascalReferenceExpression;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Mixin for reference_expression and member_expression. */
public abstract class KmrPascalReferenceExpressionMixin extends KmrPascalExpressionImpl implements KmrPascalReferenceExpression
{

	public KmrPascalReferenceExpressionMixin(@NotNull ASTNode node)
	{
		super(node);
	}

	@Override
	@NotNull
	public PsiElement getNameIdentifier()
	{
		ASTNode identifier = getNode().findChildByType(KmrPascalTypes.IDENTIFIER);
		assert identifier != null : "reference without identifier: " + getText();
		return identifier.getPsi();
	}

	@Override
	@Nullable
	public KmrPascalExpression getQualifier()
	{
		// member_expression: the first child expression is the qualifier; reference_expression has none
		PsiElement child = getFirstChild();
		return child instanceof KmrPascalExpression ? (KmrPascalExpression) child : null;
	}

	@Override
	public PsiReference getReference()
	{
		return new KmrPascalReference(this);
	}

}
