package dev.greeny.kmr.language.editor;

import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.lang.PsiStructureViewFactory;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.KmrPascalIcons;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/** Structure view: top-level declarations of the file; records show their fields, enums their values. */
public class KmrPascalStructureViewFactory implements PsiStructureViewFactory
{

	@Override
	public @Nullable StructureViewBuilder getStructureViewBuilder(@NotNull PsiFile psiFile)
	{
		return new TreeBasedStructureViewBuilder()
		{
			@Override
			public @NotNull StructureViewModel createStructureViewModel(@Nullable Editor editor)
			{
				return new Model(psiFile, editor);
			}
		};
	}

	public static final class Model extends StructureViewModelBase implements StructureViewModel.ElementInfoProvider
	{
		public Model(@NotNull PsiFile file, @Nullable Editor editor)
		{
			super(file, editor, new Element(file));
			withSuitableClasses(KmrPascalNamedElement.class);
		}

		@Override
		public Sorter @NotNull [] getSorters()
		{
			return new Sorter[]{Sorter.ALPHA_SORTER};
		}

		@Override
		public boolean isAlwaysShowsPlus(StructureViewTreeElement element)
		{
			return false;
		}

		@Override
		public boolean isAlwaysLeaf(StructureViewTreeElement element)
		{
			PsiElement value = (PsiElement) element.getValue();
			return !(value instanceof PsiFile) && !(value instanceof KmrPascalTypeDeclaration);
		}
	}

	static final class Element implements StructureViewTreeElement
	{
		private final NavigatablePsiElement element;

		Element(@NotNull NavigatablePsiElement element)
		{
			this.element = element;
		}

		@Override
		public Object getValue()
		{
			return element;
		}

		@Override
		public @NotNull ItemPresentation getPresentation()
		{
			ItemPresentation presentation = element.getPresentation();
			if (presentation != null) {
				return presentation;
			}
			return new ItemPresentation()
			{
				@Override
				public String getPresentableText()
				{
					return element instanceof PsiFile ? ((PsiFile) element).getName() : element.getText();
				}

				@Override
				public Icon getIcon(boolean unused)
				{
					return KmrPascalIcons.FILE;
				}
			};
		}

		@Override
		public TreeElement @NotNull [] getChildren()
		{
			List<TreeElement> children = new ArrayList<>();
			if (element instanceof PsiFile) {
				for (PsiElement child : element.getChildren()) {
					for (KmrPascalNamedElement declaration : KmrPascalCompilationUnit.topLevelDeclarations(child)) {
						if (!(declaration instanceof KmrPascalEnumValue)) {
							children.add(new Element(declaration));
						}
					}
				}
			} else if (element instanceof KmrPascalTypeDeclaration) {
				KmrPascalTypeElement type = ((KmrPascalTypeDeclaration) element).getTypeSpec().getTypeElement();
				if (type instanceof KmrPascalRecordType) {
					for (KmrPascalFieldIdentifier field : PsiTreeUtil.findChildrenOfType(type, KmrPascalFieldIdentifier.class)) {
						children.add(new Element(field));
					}
				} else if (type instanceof KmrPascalEnumType) {
					for (KmrPascalEnumValue value : ((KmrPascalEnumType) type).getEnumValueList()) {
						children.add(new Element(value));
					}
				}
			}
			return children.toArray(TreeElement.EMPTY_ARRAY);
		}

		@Override
		public void navigate(boolean requestFocus)
		{
			element.navigate(requestFocus);
		}

		@Override
		public boolean canNavigate()
		{
			return element.canNavigate();
		}

		@Override
		public boolean canNavigateToSource()
		{
			return element.canNavigateToSource();
		}
	}

}
