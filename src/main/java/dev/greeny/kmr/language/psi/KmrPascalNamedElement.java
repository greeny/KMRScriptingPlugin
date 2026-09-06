package dev.greeny.kmr.language.psi;

import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;

/**
 * Anything that declares a name: routines, constants, types, variables, parameters, record fields, enum values.
 * The name identifier is the first IDENTIFIER token of the element.
 */
public interface KmrPascalNamedElement extends PsiNameIdentifierOwner, NavigatablePsiElement
{
}
