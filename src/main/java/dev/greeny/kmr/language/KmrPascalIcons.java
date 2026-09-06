package dev.greeny.kmr.language;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class KmrPascalIcons
{

	public static final Icon FILE = IconLoader.getIcon("icons/file-icon.png", KmrPascalIcons.class);

	/** Gutter icon of game event handlers. */
	public static final Icon EVENT_HANDLER = AllIcons.Gutter.ImplementingMethod;

	/** Icon by declaration kind, shared by completion, structure view and navigation. */
	@NotNull
	public static Icon forDeclaration(@NotNull PsiElement declaration)
	{
		if (declaration instanceof KmrPascalProcedureDeclaration) return AllIcons.Nodes.Method;
		if (declaration instanceof KmrPascalFunctionDeclaration) return AllIcons.Nodes.Function;
		if (declaration instanceof KmrPascalConstantDeclaration) return AllIcons.Nodes.Constant;
		if (declaration instanceof KmrPascalTypeDeclaration) {
			KmrPascalTypeElement type = ((KmrPascalTypeDeclaration) declaration).getTypeSpec().getTypeElement();
			if (type instanceof KmrPascalEnumType) return AllIcons.Nodes.Enum;
			if (type instanceof KmrPascalRecordType) return AllIcons.Nodes.Record;
			return AllIcons.Nodes.Type;
		}
		if (declaration instanceof KmrPascalParameterIdentifier) return AllIcons.Nodes.Parameter;
		if (declaration instanceof KmrPascalFieldIdentifier) {
			return KmrPascalCallable.of(declaration) != null ? AllIcons.Nodes.Method : AllIcons.Nodes.Field;
		}
		if (declaration instanceof KmrPascalEnumValue) return AllIcons.Nodes.Enum;
		KmrPascalCallable callable = KmrPascalCallable.of(declaration);
		if (callable != null) {
			return callable.isFunction() ? AllIcons.Nodes.Function : AllIcons.Nodes.Method; // System.script routines
		}
		return AllIcons.Nodes.Variable;
	}

}
