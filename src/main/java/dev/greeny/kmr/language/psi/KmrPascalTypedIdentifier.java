package dev.greeny.kmr.language.psi;

import org.jetbrains.annotations.Nullable;

/** A declared name that has a type: variable, parameter, record field. */
public interface KmrPascalTypedIdentifier extends KmrPascalNamedElement
{

	/** The type-spec of the declaration this identifier belongs to ({@code a, b: Integer} -> Integer for both). */
	@Nullable
	KmrPascalTypeSpec getType();

}
