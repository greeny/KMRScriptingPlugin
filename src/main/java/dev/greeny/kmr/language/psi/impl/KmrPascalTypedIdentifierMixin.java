package dev.greeny.kmr.language.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.KmrPascalTypeSpec;
import dev.greeny.kmr.language.psi.KmrPascalTypedIdentifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Mixin for var-identifier, parameter-identifier and field-identifier: the type is a sibling type-spec node. */
public abstract class KmrPascalTypedIdentifierMixin extends KmrPascalNamedElementImpl implements KmrPascalTypedIdentifier
{

	public KmrPascalTypedIdentifierMixin(@NotNull ASTNode node)
	{
		super(node);
	}

	@Override
	@Nullable
	public KmrPascalTypeSpec getType()
	{
		PsiElement parent = getParent();
		return parent == null ? null : PsiTreeUtil.getChildOfType(parent, KmrPascalTypeSpec.class);
	}

}
