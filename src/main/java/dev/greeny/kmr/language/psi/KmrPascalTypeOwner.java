package dev.greeny.kmr.language.psi;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;

/** Implemented by type-spec: gives access to the concrete type element it wraps. */
public interface KmrPascalTypeOwner extends PsiElement
{

	@Nullable
	KmrPascalTypeElement getTypeElement();

}
