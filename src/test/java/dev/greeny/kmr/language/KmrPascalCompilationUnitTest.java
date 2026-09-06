package dev.greeny.kmr.language;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.ui.EditorNotificationPanel;
import dev.greeny.kmr.language.editor.KmrPascalResolveContextNotificationProvider;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.greeny.kmr.language.psi.KmrPascalConstantDeclaration;
import dev.greeny.kmr.language.psi.KmrPascalVarIdentifier;
import dev.greeny.kmr.language.unit.KmrPascalCompilationUnit;
import dev.greeny.kmr.language.unit.KmrPascalEntryPoints;
import dev.greeny.kmr.language.unit.KmrPascalResolveContextService;

import javax.swing.*;
import java.util.List;
import java.util.function.Function;

/** Entry points, includes, preprocessor and resolve contexts across several files. */
public class KmrPascalCompilationUnitTest extends BasePlatformTestCase
{

	public void testEntryPointDetection()
	{
		PsiFile main = myFixture.addFileToProject("main.script", "");
		myFixture.addFileToProject("main.dat", "");
		PsiFile other = myFixture.addFileToProject("Other.script", "");
		myFixture.addFileToProject("other.MAP", "");
		PsiFile shared = myFixture.addFileToProject("shared.script", "");

		assertTrue(KmrPascalEntryPoints.isEntryPoint(getProject(), main.getVirtualFile()));
		assertTrue(KmrPascalEntryPoints.isEntryPoint(getProject(), other.getVirtualFile()));
		assertFalse(KmrPascalEntryPoints.isEntryPoint(getProject(), shared.getVirtualFile()));
	}

	public void testSharedFileResolvesGlobalsOfItsEntryPoint()
	{
		myFixture.addFileToProject("main.dat", "");
		myFixture.addFileToProject("main.script", "var Score: Integer;\n{$I shared.script}\nprocedure OnTick; begin Bump; end;");
		PsiFile shared = myFixture.configureByText("shared.script", "procedure Bump; begin Sco<caret>re := Score + 1; end;");

		assertInstanceOf(resolveAtCaret(), KmrPascalVarIdentifier.class);
		assertEquals("main.script", KmrPascalResolveContextService.getInstance(getProject()).getContextFor(shared).getName());
	}

	public void testEntryPointResolvesDeclarationsOfIncludes()
	{
		myFixture.addFileToProject("main.dat", "");
		myFixture.addFileToProject("lib/util.script", "const MAX = 5;");
		myFixture.configureByText("main.script", "{$INCLUDE lib\\util.script}\nprocedure OnTick; begin x := MA<caret>X; end;");

		assertInstanceOf(resolveAtCaret(), KmrPascalConstantDeclaration.class);
	}

	public void testIncludeDirectiveIsReferenceToFile()
	{
		PsiFile shared = myFixture.addFileToProject("Shared.script", "");
		myFixture.configureByText("main.script", "{$I sha<caret>red.script}");

		PsiReference reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
		assertNotNull(reference);
		assertEquals(shared, reference.resolve());
	}

	public void testGuardedDoubleIncludeHasNoDuplicates()
	{
		myFixture.addFileToProject("main.dat", "");
		myFixture.addFileToProject("consts.script", "{$IFNDEF constsScript}\n{$DEFINE constsScript}\nconst X = 1;\n{$ENDIF}\n");
		PsiFile main = myFixture.configureByText("main.script", "{$I consts.script}\n{$I consts.script}\nprocedure P; begin y := <caret>X; end;");

		PsiPolyVariantReference reference = (PsiPolyVariantReference) main.findReferenceAt(myFixture.getCaretOffset());
		assertEquals(1, reference.multiResolve(false).length);

		KmrPascalCompilationUnit unit = KmrPascalCompilationUnit.of(main);
		assertEquals(3, unit.getInclusions().size());
		assertTrue(unit.getDefines().contains("constsscript"));
		assertEmpty(unit.getProblems());
		assertEquals(1, unit.findGlobals("x").size());
		// the second inclusion of consts.script has the constant inactive
		KmrPascalCompilationUnit.Inclusion second = unit.getInclusions().get(2);
		assertEquals("consts.script", second.file.getName());
		assertFalse(second.isActive(second.file.getText().indexOf("const X")));
		assertTrue(unit.getInclusions().get(1).isActive(second.file.getText().indexOf("const X")));
	}

	public void testUnguardedDoubleIncludeIsReported()
	{
		myFixture.addFileToProject("consts.script", "const X = 1;");
		PsiFile main = myFixture.configureByText("main.script", "{$I consts.script}\n{$I consts.script}\n");

		KmrPascalCompilationUnit unit = KmrPascalCompilationUnit.of(main);
		assertEquals(1, unit.getProblems().size());
		assertEquals(KmrPascalCompilationUnit.Problem.Kind.DUPLICATE_ACTIVE_INCLUDE, unit.getProblems().get(0).kind);
		assertEquals(main, unit.getProblems().get(0).file);
	}

	public void testMissingIncludeAndUnbalancedConditionalAreReported()
	{
		PsiFile main = myFixture.configureByText("main.script", "{$I nope.script}\n{$IFDEF x}\n{$ENDIF}\n{$ENDIF}\n{$IFNDEF y}\n");
		List<KmrPascalCompilationUnit.Problem> problems = KmrPascalCompilationUnit.of(main).getProblems();
		assertEquals(3, problems.size());
		assertEquals(KmrPascalCompilationUnit.Problem.Kind.MISSING_INCLUDE, problems.get(0).kind);
		assertEquals(KmrPascalCompilationUnit.Problem.Kind.ENDIF_WITHOUT_IF, problems.get(1).kind);
		assertEquals(KmrPascalCompilationUnit.Problem.Kind.UNBALANCED_CONDITIONAL, problems.get(2).kind);
	}

	public void testConditionalCompilationFollowsDefinesAcrossFiles()
	{
		myFixture.addFileToProject("main.dat", "");
		myFixture.addFileToProject("main.script", "{$DEFINE debug}\n{$I shared.script}\nprocedure P; begin A := 1; B := 2; end;");
		PsiFile shared = myFixture.configureByText("shared.script", "{$IFDEF debug}\nvar A: Integer;\n{$ELSE}\nvar B: Integer;\n{$ENDIF}\n");

		KmrPascalCompilationUnit unit = KmrPascalResolveContextService.getInstance(getProject()).getUnitFor(shared);
		assertEquals("main.script", unit.getEntryPoint().getName());
		assertNotNull(unit.findGlobals("a"));
		assertEquals(1, unit.findGlobals("A").size());
		assertEquals(0, unit.findGlobals("B").size());

		List<TextRange> inactive = unit.inactiveRanges(shared);
		assertEquals(1, inactive.size());
		assertEquals("var B: Integer;", shared.getText().substring(inactive.get(0).getStartOffset(), inactive.get(0).getEndOffset()).trim());

		// and the editor dims it
		List<HighlightInfo> infos = myFixture.doHighlighting();
		assertTrue(infos.stream().anyMatch(info -> info.forcedTextAttributesKey == KmrPascalSyntaxHighlighter.INACTIVE_CODE
			&& info.getStartOffset() == inactive.get(0).getStartOffset() && info.getEndOffset() == inactive.get(0).getEndOffset()));
	}

	public void testResolveContextCanBeSwitchedBetweenEntryPoints()
	{
		myFixture.addFileToProject("a/mapA.dat", "");
		myFixture.addFileToProject("a/mapA.script", "var X: Integer;\n{$I ../shared/common.script}");
		myFixture.addFileToProject("b/mapB.dat", "");
		myFixture.addFileToProject("b/mapB.script", "const X = 1;\n{$I ../shared/common.script}");
		PsiFile shared = myFixture.addFileToProject("shared/common.script", "procedure P; begin y := X; end;");
		VirtualFile sharedFile = shared.getVirtualFile();
		myFixture.configureFromExistingVirtualFile(sharedFile);
		myFixture.getEditor().getCaretModel().moveToOffset(shared.getText().indexOf("X;"));

		KmrPascalResolveContextService service = KmrPascalResolveContextService.getInstance(getProject());
		List<VirtualFile> candidates = service.getCandidates(sharedFile);
		assertEquals(2, candidates.size());
		assertEquals("mapA.script", candidates.get(0).getName());

		assertInstanceOf(resolveAtCaret(), KmrPascalVarIdentifier.class);

		service.setContext(sharedFile, candidates.get(1));
		assertEquals("mapB.script", service.getContextFor(shared).getName());
		assertInstanceOf(resolveAtCaret(), KmrPascalConstantDeclaration.class);

		// the banner reflects the chosen context and offers a switch
		Function<? super FileEditor, ? extends JComponent> data = new KmrPascalResolveContextNotificationProvider().collectNotificationData(getProject(), sharedFile);
		assertNotNull(data);
		TextEditor textEditor = TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor());
		EditorNotificationPanel panel = (EditorNotificationPanel) data.apply(textEditor);
		assertNotNull(panel);
		assertTrue(panel.getText(), panel.getText().contains("mapB.script") && panel.getText().contains("2 entry points"));

		// entry points themselves get no banner
		assertNull(new KmrPascalResolveContextNotificationProvider().collectNotificationData(getProject(), candidates.get(0)));
	}

	public void testDumbModeDoesNotTouchTheIndex()
	{
		myFixture.addFileToProject("main.dat", "");
		myFixture.addFileToProject("main.script", "{$I shared.script}");
		PsiFile shared = myFixture.configureByText("shared.script", "procedure P; begin end;");
		VirtualFile sharedFile = shared.getVirtualFile();

		com.intellij.openapi.project.DumbServiceImpl dumbService = com.intellij.openapi.project.DumbServiceImpl.getInstance(getProject());
		dumbService.setDumb(true);
		try {
			assertEmpty(dev.greeny.kmr.language.unit.KmrPascalIncludeIndex.filesIncluding(getProject(), "shared.script"));
			assertNull(new KmrPascalResolveContextNotificationProvider().collectNotificationData(getProject(), sharedFile));
			assertEquals("r16020", dev.greeny.kmr.language.editor.KmrPascalVersionStatusBarWidget.effectiveVersion(getProject(), sharedFile));
			// a context computed while dumb is provisional ...
			assertEquals(shared, KmrPascalResolveContextService.getInstance(getProject()).getContextFor(shared));
		} finally {
			dumbService.setDumb(false);
		}
		// ... and is recomputed once indexing is over
		assertEquals("main.script", KmrPascalResolveContextService.getInstance(getProject()).getContextFor(shared).getName());
		assertNotNull(new KmrPascalResolveContextNotificationProvider().collectNotificationData(getProject(), sharedFile));
	}

	public void testStandaloneFileIsItsOwnUnit()
	{
		PsiFile file = myFixture.configureByText("lonely.script", "var A: Integer; procedure P; begin <caret>A := 1; end;");
		assertEquals(file, KmrPascalResolveContextService.getInstance(getProject()).getContextFor(file));
		assertInstanceOf(resolveAtCaret(), KmrPascalVarIdentifier.class);
	}

	// ---------------------------------------------------------------------------------------------------------------

	private PsiElement resolveAtCaret()
	{
		PsiReference reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
		assertNotNull("no reference at caret", reference);
		return reference.resolve();
	}

}
