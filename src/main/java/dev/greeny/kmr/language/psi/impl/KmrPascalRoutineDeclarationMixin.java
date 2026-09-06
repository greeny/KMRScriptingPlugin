package dev.greeny.kmr.language.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.psi.KmrPascalParameterDeclaration;
import dev.greeny.kmr.language.psi.KmrPascalRoutineDeclaration;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Mixin for procedure-declaration and function-declaration. */
public abstract class KmrPascalRoutineDeclarationMixin extends KmrPascalNamedElementImpl implements KmrPascalRoutineDeclaration
{

	public KmrPascalRoutineDeclarationMixin(@NotNull ASTNode node)
	{
		super(node);
	}

	@Override
	@NotNull
	public List<KmrPascalParameterDeclaration> getParameters()
	{
		return PsiTreeUtil.getChildrenOfTypeAsList(this, KmrPascalParameterDeclaration.class);
	}

}
