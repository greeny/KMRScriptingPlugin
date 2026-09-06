package dev.greeny.kmr.language.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.KmrPascalTypeElement;
import dev.greeny.kmr.language.psi.KmrPascalTypeOwner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Mixin for type-spec: the wrapper around one concrete type element. */
public abstract class KmrPascalTypeSpecMixin extends ASTWrapperPsiElement implements KmrPascalTypeOwner
{

	public KmrPascalTypeSpecMixin(@NotNull ASTNode node)
	{
		super(node);
	}

	@Override
	@Nullable
	public KmrPascalTypeElement getTypeElement()
	{
		return PsiTreeUtil.getChildOfType(this, KmrPascalTypeElement.class);
	}

}
