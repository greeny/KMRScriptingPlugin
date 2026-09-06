package dev.greeny.kmr.language.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import dev.greeny.kmr.language.KmrPascalIcons;
import dev.greeny.kmr.language.psi.KmrPascalElementFactory;
import dev.greeny.kmr.language.psi.KmrPascalNamedElement;
import dev.greeny.kmr.language.psi.KmrPascalTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/** Mixin for all declaring elements: the name is the first IDENTIFIER child token. */
public abstract class KmrPascalNamedElementImpl extends ASTWrapperPsiElement implements KmrPascalNamedElement
{

	public KmrPascalNamedElementImpl(@NotNull ASTNode node)
	{
		super(node);
	}

	@Override
	@Nullable
	public PsiElement getNameIdentifier()
	{
		ASTNode identifier = getNode().findChildByType(KmrPascalTypes.IDENTIFIER);
		return identifier == null ? null : identifier.getPsi();
	}

	@Override
	@Nullable
	public String getName()
	{
		PsiElement identifier = getNameIdentifier();
		return identifier == null ? null : identifier.getText();
	}

	@Override
	public PsiElement setName(@NotNull String name) throws IncorrectOperationException
	{
		PsiElement identifier = getNameIdentifier();
		if (identifier == null) {
			throw new IncorrectOperationException("Element has no name identifier");
		}
		identifier.replace(KmrPascalElementFactory.createIdentifier(getProject(), name));
		return this;
	}

	@Override
	public int getTextOffset()
	{
		PsiElement identifier = getNameIdentifier();
		return identifier == null ? super.getTextOffset() : identifier.getTextOffset();
	}

	@Override
	public ItemPresentation getPresentation()
	{
		return new ItemPresentation()
		{
			@Override
			public String getPresentableText()
			{
				dev.greeny.kmr.language.resolve.KmrPascalCallable callable = dev.greeny.kmr.language.resolve.KmrPascalCallable.of(KmrPascalNamedElementImpl.this);
				if (callable != null) {
					return getName() + callable.getSignatureText();
				}
				if (KmrPascalNamedElementImpl.this instanceof dev.greeny.kmr.language.psi.KmrPascalTypedIdentifier) {
					dev.greeny.kmr.language.psi.KmrPascalTypeSpec type = ((dev.greeny.kmr.language.psi.KmrPascalTypedIdentifier) KmrPascalNamedElementImpl.this).getType();
					return type == null ? getName() : getName() + ": " + dev.greeny.kmr.language.types.KmrTypePresenter.typeText(type);
				}
				return getName();
			}

			@Override
			public String getLocationString()
			{
				return getContainingFile().getName();
			}

			@Override
			public Icon getIcon(boolean unused)
			{
				return KmrPascalIcons.forDeclaration(KmrPascalNamedElementImpl.this);
			}
		};
	}

}
