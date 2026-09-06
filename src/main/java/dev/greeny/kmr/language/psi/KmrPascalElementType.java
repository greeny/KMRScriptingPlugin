package dev.greeny.kmr.language.psi;

import com.intellij.psi.tree.IElementType;
import dev.greeny.kmr.language.KmrPascalLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class KmrPascalElementType extends IElementType
{

	public KmrPascalElementType(@NotNull @NonNls String debugName)
	{
		super(debugName, KmrPascalLanguage.INSTANCE);
	}

}
