package dev.greeny.kmr.language.psi;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import dev.greeny.kmr.language.KmrPascalFileType;
import dev.greeny.kmr.language.KmrPascalLanguage;
import org.jetbrains.annotations.NotNull;

public class KmrPascalFile extends PsiFileBase
{

	public KmrPascalFile(@NotNull FileViewProvider viewProvider)
	{
		super(viewProvider, KmrPascalLanguage.INSTANCE);
	}

	@NotNull
	@Override
	public FileType getFileType()
	{
		return KmrPascalFileType.INSTANCE;
	}

	@Override
	public String toString()
	{
		return "PascalScript File";
	}

}
