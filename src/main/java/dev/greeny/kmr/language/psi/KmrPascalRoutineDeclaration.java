package dev.greeny.kmr.language.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Common view of procedure-declaration and function-declaration. */
public interface KmrPascalRoutineDeclaration extends KmrPascalNamedElement
{

	@NotNull
	List<KmrPascalParameterDeclaration> getParameters();

	@Nullable
	KmrPascalStatementList getStatementList();

	/** Return type element of a function; null for procedures. */
	@Nullable
	default KmrPascalTypeSpec getReturnType()
	{
		return this instanceof KmrPascalFunctionDeclaration ? ((KmrPascalFunctionDeclaration) this).getTypeSpec() : null;
	}

}
