package dev.greeny.kmr.language;

import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class KmrPascalFileType extends LanguageFileType
{

	public static final KmrPascalFileType INSTANCE = new KmrPascalFileType();

	private KmrPascalFileType()
	{
		super(KmrPascalLanguage.INSTANCE);
	}

	@NotNull
	@Override
	public String getName()
	{
		return "PascalScript file";
	}

	@NotNull
	@Override
	public String getDescription()
	{
		return "PascalScript file with support for KMR procedures";
	}

	@NotNull
	@Override
	public String getDefaultExtension()
	{
		return "script";
	}

	@Override
	public Icon getIcon()
	{
		return KmrPascalIcons.FILE;
	}

}
