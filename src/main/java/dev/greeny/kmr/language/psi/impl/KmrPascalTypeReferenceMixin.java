package dev.greeny.kmr.language.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import dev.greeny.kmr.language.psi.KmrPascalReference;
import dev.greeny.kmr.language.psi.KmrPascalReferenceElement;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;

/** Mixin for type-reference (a type name used in a declaration). */
public abstract class KmrPascalTypeReferenceMixin extends ASTWrapperPsiElement implements KmrPascalReferenceElement
{

	public KmrPascalTypeReferenceMixin(@NotNull ASTNode node)
	{
		super(node);
	}

	@Override
	@NotNull
	public PsiElement getNameIdentifier()
	{
		ASTNode identifier = getNode().findChildByType(KmrPascalTypes.IDENTIFIER);
		assert identifier != null : "type reference without identifier: " + getText();
		return identifier.getPsi();
	}

	@Override
	public PsiReference getReference()
	{
		return new KmrPascalReference(this);
	}

}
