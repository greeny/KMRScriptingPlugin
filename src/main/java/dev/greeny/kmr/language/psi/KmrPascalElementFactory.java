package dev.greeny.kmr.language.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.KmrPascalFileType;
import org.jetbrains.annotations.NotNull;

public final class KmrPascalElementFactory
{

	private KmrPascalElementFactory()
	{
	}

	@NotNull
	public static KmrPascalFile createFile(@NotNull Project project, @NotNull String text)
	{
		return (KmrPascalFile) PsiFileFactory.getInstance(project).createFileFromText("dummy.script", KmrPascalFileType.INSTANCE, text);
	}

	/** An IDENTIFIER leaf with the given text, for renames. */
	@NotNull
	public static PsiElement createIdentifier(@NotNull Project project, @NotNull String name)
	{
		KmrPascalFile file = createFile(project, "var " + name + ": Integer;");
		KmrPascalVarIdentifier identifier = PsiTreeUtil.findChildOfType(file, KmrPascalVarIdentifier.class);
		PsiElement leaf = identifier == null ? null : identifier.getNameIdentifier();
		if (leaf == null) {
			throw new IllegalArgumentException("Not a valid identifier: " + name);
		}
		return leaf;
	}

}
