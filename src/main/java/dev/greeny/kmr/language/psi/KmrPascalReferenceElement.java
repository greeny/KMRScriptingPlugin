package dev.greeny.kmr.language.psi;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An element that refers to a declaration by name: a bare identifier expression, a member access (the node is the
 * reference to the member, {@link #getQualifier()} is the left side) or a type name.
 */
public interface KmrPascalReferenceElement extends PsiElement
{

	/** The IDENTIFIER token holding the referenced name. */
	@NotNull
	PsiElement getNameIdentifier();

	@NotNull
	default String getReferenceName()
	{
		return getNameIdentifier().getText();
	}

	/** Left side of a member access, null for unqualified references. */
	@Nullable
	default KmrPascalExpression getQualifier()
	{
		return null;
	}

}
