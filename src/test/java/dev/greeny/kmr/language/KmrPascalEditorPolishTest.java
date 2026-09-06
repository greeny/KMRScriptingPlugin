package dev.greeny.kmr.language;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.codeInsight.template.impl.TemplateSettings;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ide.fileTemplates.FileTemplateUtil;
import com.intellij.psi.PsiDocCommentBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.greeny.kmr.language.stubs.KmrPascalStubLibrary;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.util.CommonProcessors;
import dev.greeny.kmr.language.editor.KmrPascalBreadcrumbsProvider;
import dev.greeny.kmr.language.editor.KmrPascalCreateFileAction;
import dev.greeny.kmr.language.editor.KmrPascalDocumentationProvider;
import dev.greeny.kmr.language.editor.KmrPascalGotoSymbolContributor;
import dev.greeny.kmr.language.editor.KmrPascalRenameInputValidator;
import dev.greeny.kmr.language.editor.KmrPascalStructureViewFactory;
import dev.greeny.kmr.language.psi.KmrPascalProcedureDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Editor polish: formatter, structure view, folding, gutter markers, live templates, file templates, go to symbol. */
public class KmrPascalEditorPolishTest extends BasePlatformTestCase
{

	public void testReformat()
	{
		myFixture.configureByText("a.script", """
			type
			TRec=record
			X,Y : Integer;
			end;
			var
			Counter:Integer;
			procedure OnTick(a,b:Integer);
			var
			I:Integer;
			begin
			for I:=0 to 3 do
			begin
			Counter:=Counter+I*2;
			Actions.ShowMsg( -1 , 'x' );
			if Counter>10 then
			Counter:=-Counter
			else
			Counter := 0;
			end;
			case Counter of
			0: Exit;
			1,2:
			Counter:=3;
			else
			Exit;
			end;
			repeat
			Inc(I);
			until I>3;
			with TRec do X:=1;
			end;
			""");
		WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () -> CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile()));
		myFixture.checkResult("""
			type
				TRec = record
					X, Y: Integer;
				end;
			var
				Counter: Integer;
			procedure OnTick(a, b: Integer);
			var
				I: Integer;
			begin
				for I := 0 to 3 do
				begin
					Counter := Counter + I * 2;
					Actions.ShowMsg(-1, 'x');
					if Counter > 10 then
						Counter := -Counter
					else
						Counter := 0;
				end;
				case Counter of
					0: Exit;
					1, 2:
						Counter := 3;
					else
						Exit;
				end;
				repeat
					Inc(I);
				until I > 3;
				with TRec do X := 1;
			end;
			""");
	}

	public void testStructureView()
	{
		PsiFile file = myFixture.configureByText("a.script", """
			const C = 1;
			type TRec = record X, Y: Integer; end; TE = (eA, eB);
			var V: Integer;
			procedure P(a: Integer); begin end;
			function F: Boolean; begin end;
			""");
		KmrPascalStructureViewFactory.Model model = new KmrPascalStructureViewFactory.Model(file, myFixture.getEditor());
		List<String> names = new ArrayList<>();
		for (TreeElement child : model.getRoot().getChildren()) {
			names.add(child.getPresentation().getPresentableText());
		}
		assertEquals(List.of("C", "TRec", "TE", "V: Integer", "P(a: Integer)", "F: Boolean"), names);
		StructureViewTreeElement record = (StructureViewTreeElement) model.getRoot().getChildren()[1];
		assertEquals(2, record.getChildren().length);
		assertEquals("X: Integer", record.getChildren()[0].getPresentation().getPresentableText());
		StructureViewTreeElement enumType = (StructureViewTreeElement) model.getRoot().getChildren()[2];
		assertEquals("eA", enumType.getChildren()[0].getPresentation().getPresentableText());
		model.dispose();
	}

	public void testFolding()
	{
		myFixture.testFolding(getTestDataPath() + "/folding/Folding.script");
	}

	public void testEventHandlerGutterMarker()
	{
		myFixture.configureByText("a.script", """
			procedure OnTick; begin end;
			{$EVENT evtHouseBuilt:MyHandler}
			procedure MyHandler(aHouse: Integer); begin end;
			procedure Helper; begin end;
			""");
		List<GutterMark> gutters = myFixture.findAllGutters();
		List<String> tooltips = gutters.stream().map(GutterMark::getTooltipText).filter(t -> t != null && t.startsWith("Game event handler")).toList();
		assertEquals(tooltips.toString(), 3, tooltips.size());
		assertTrue(tooltips.get(0), tooltips.get(0).startsWith("Game event handler for evtTick"));
		assertTrue(tooltips.get(1), tooltips.get(1).startsWith("Game event handler registration: MyHandler handles evtHouseBuilt"));
		assertTrue(tooltips.get(2), tooltips.get(2).startsWith("Game event handler for evtHouseBuilt"));
	}

	public void testLiveTemplatesAndFileTemplates()
	{
		TemplateImpl template = TemplateSettings.getInstance().getTemplate("proc", "KMR PascalScript");
		assertNotNull(template);
		assertTrue(template.getString().startsWith("procedure $NAME$;"));
		assertNotNull(TemplateSettings.getInstance().getTemplate("guard", "KMR PascalScript"));

		myFixture.configureByText("a.script", "pro<caret>");
		myFixture.type("c");
		myFixture.performEditorAction("ExpandLiveTemplateByTab");
		assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().startsWith("procedure ;\nbegin\n  \nend;"));

		FileTemplate emptyTemplate = FileTemplateManager.getInstance(getProject()).getInternalTemplate(KmrPascalCreateFileAction.TEMPLATE);
		assertEquals("", emptyTemplate.getText().trim());
		FileTemplate guardedTemplate = FileTemplateManager.getInstance(getProject()).getInternalTemplate(KmrPascalCreateFileAction.GUARDED_TEMPLATE);
		// "$" that is not a template variable is written as ${DS} (Velocity ignores "\$" for undefined references)
		Properties properties = new Properties(FileTemplateManager.getInstance(getProject()).getDefaultProperties());
		properties.setProperty("NAME", "Shared");
		String rendered = FileTemplateUtil.mergeTemplate(properties, guardedTemplate.getText(), false);
		assertTrue(rendered, rendered.startsWith("{$IFNDEF SharedScript}\n{$DEFINE SharedScript}\n"));
		assertTrue(rendered, rendered.trim().endsWith("{$ENDIF}") && !rendered.contains("//"));
	}

	public void testRenderedDocComments()
	{
		myFixture.configureByText("a.script", """
			{
				Counts something.
				@since 5057
				@param aHand the player
				@kind aHand handIndex
			}
			procedure Count(aHand: Integer); begin end;
			{ not a doc comment: a blank line follows }

			var X: Integer;
			// line comments are not rendered
			var Y: Integer;
			""");
		KmrPascalDocumentationProvider provider = new KmrPascalDocumentationProvider();
		List<PsiDocCommentBase> comments = new ArrayList<>();
		provider.collectDocComments(myFixture.getFile(), comments::add);
		assertEquals(1, comments.size());
		assertInstanceOf(comments.get(0).getOwner(), KmrPascalProcedureDeclaration.class);
		String rendered = provider.generateRenderedDoc(comments.get(0));
		assertNotNull(rendered);
		assertTrue(rendered, rendered.contains("Counts something.") && rendered.contains("Available since") && rendered.contains("<code>aHand</code> &ndash; the player")
			&& rendered.contains("Kinds") && rendered.contains("handIndex"));
		// the API stubs are rendered too
		PsiFile actions = KmrPascalStubLibrary.getInstance(getProject()).getStubSet(KmrPascalStubLibrary.LATEST).scopePsiFiles(getProject()).get(1);
		List<PsiDocCommentBase> stubComments = new ArrayList<>();
		provider.collectDocComments(actions, stubComments::add);
		assertTrue(stubComments.size() > 100);
	}

	public void testDocTagColouring()
	{
		List<KmrPascalAnnotator.DocTagLine> lines = KmrPascalAnnotator.docTagLines("{\n\tDescribes.\n\t@since 5057\n\t@param aHand the player\n\tno tag @here\n}");
		assertEquals(2, lines.size());
		String text = "{\n\tDescribes.\n\t@since 5057\n\t@param aHand the player\n\tno tag @here\n}";
		assertEquals("@since", text.substring(lines.get(0).tagStart, lines.get(0).tagEnd));
		assertEquals(" 5057", text.substring(lines.get(0).tagEnd, lines.get(0).lineEnd));
		assertEquals("@param", text.substring(lines.get(1).tagStart, lines.get(1).tagEnd));
		assertEquals(" aHand the player", text.substring(lines.get(1).tagEnd, lines.get(1).lineEnd));
		List<KmrPascalAnnotator.DocTagLine> line = KmrPascalAnnotator.docTagLines("// @deprecated Use Newer");
		assertEquals(1, line.size());
		assertEquals(3, line.get(0).tagStart);
	}

	public void testBreadcrumbs()
	{
		myFixture.configureByText("a.script", """
			type TRec = record X: Integer; end;
			procedure OnTick;
			var I: Integer;
			begin
			  for I := 0 to 3 do
			    if I > 1 then
			      case I of
			        2: Actions.Show<caret>Msg(0, 'x');
			      end;
			end;
			""");
		KmrPascalBreadcrumbsProvider provider = new KmrPascalBreadcrumbsProvider();
		List<String> crumbs = new ArrayList<>();
		for (PsiElement element = myFixture.getFile().findElementAt(myFixture.getCaretOffset()); element != null; element = element.getParent()) {
			if (provider.acceptElement(element)) {
				crumbs.add(0, provider.getElementInfo(element));
			}
		}
		assertEquals(List.of("OnTick", "for I", "if I > 1", "case I"), crumbs);
		assertEquals("procedure OnTick", provider.getElementTooltip(PsiTreeUtil.findChildOfType(myFixture.getFile(), KmrPascalProcedureDeclaration.class)));
	}

	public void testRenameValidationAndConflicts()
	{
		assertNull(KmrPascalRenameInputValidator.validate("MyName_2"));
		assertEquals("'begin' is a reserved word", KmrPascalRenameInputValidator.validate("begin"));
		assertEquals("'my name' is not a valid identifier", KmrPascalRenameInputValidator.validate("my name"));
		assertEquals("'2x' is not a valid identifier", KmrPascalRenameInputValidator.validate("2x"));

		myFixture.configureByText("a.script", """
			var Counter, Other: Integer;
			procedure P(aValue: Integer);
			var Local: Integer;
			begin
			  Lo<caret>cal := aValue + Counter;
			end;
			""");
		assertConflict("Actions", "'Actions' is already defined by the KaM Remake API");
		assertConflict("S", "'S' is already defined by the KaM Remake API");
		assertConflict("Length", "'Length' is already defined by PascalScript's standard library");
		assertConflict("aValue", "'aValue' is already declared in procedure P");
		assertConflict("p", "'p' is already declared in a.script");
		myFixture.renameElementAtCaret("Fresh");
		assertTrue(myFixture.getFile().getText().contains("var Fresh: Integer;"));

		myFixture.configureByText("b.script", "var Coun<caret>ter, Other: Integer;");
		assertConflict("other", "'other' is already declared in b.script");
	}

	private void assertConflict(String newName, String expectedMessage)
	{
		try {
			myFixture.renameElementAtCaret(newName);
			fail("expected a conflict for " + newName);
		} catch (BaseRefactoringProcessor.ConflictsInTestsException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
		}
	}

	public void testGotoSymbol()
	{
		myFixture.addFileToProject("a.script", "var Counter: Integer;\nprocedure Helper; begin end;");
		myFixture.addFileToProject("b.script", "const MAX = 3;");
		CommonProcessors.CollectProcessor<String> names = new CommonProcessors.CollectProcessor<>();
		new KmrPascalGotoSymbolContributor().processNames(names, GlobalSearchScope.projectScope(getProject()), null);
		assertContainsElements(names.getResults(), "Counter", "Helper", "MAX");
	}

	@Override
	protected String getTestDataPath()
	{
		return "src/test/testData";
	}

}
