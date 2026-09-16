package dev.greeny.kmr.language;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext;
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext;
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext;
import dev.greeny.kmr.language.editor.KmrPascalDocumentationProvider;
import dev.greeny.kmr.language.editor.KmrPascalParameterInfoHandler;
import dev.greeny.kmr.language.editor.KmrPascalVersionStatusBarWidget;
import dev.greeny.kmr.language.psi.KmrPascalCallExpression;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.KmrPascalEvent;
import dev.greeny.kmr.language.stubs.KmrPascalEvents;
import dev.greeny.kmr.language.unit.KmrPascalProjectSettings;

import java.util.List;

/** Completion, parameter info, quick documentation, status bar version. */
public class KmrPascalCodeInsightTest extends BasePlatformTestCase
{

	// ---------------------------------------------------------------------------------------------------------------
	// completion
	// ---------------------------------------------------------------------------------------------------------------

	public void testMembersAfterDot()
	{
		List<String> items = complete("procedure OnTick; begin Actions.<caret> end;");
		assertContainsElements(items, "ShowMsg", "GiveUnitEx", "PlayerDefeat");
		assertDoesntContain(items, "TKMScriptActions", "GameTime", "begin");

		assertContainsElements(complete("procedure OnTick; begin S.<caret> end;"), "GameTime", "PlayerIsAI");
		assertContainsElements(complete("var pt: TKMPoint; procedure P; begin pt.<caret> := 1; end;"), "X", "Y");
	}

	public void testMemberCompletionIsCaseInsensitiveAndInsertsParentheses()
	{
		myFixture.configureByText("a.script", "procedure OnTick; begin Actions.showm<caret> end;");
		myFixture.completeBasic();
		LookupElement showMsg = myFixture.getLookupElements() == null ? null
			: java.util.Arrays.stream(myFixture.getLookupElements()).filter(e -> e.getLookupString().equals("ShowMsg")).findFirst().orElse(null);
		assertNotNull(showMsg);
		myFixture.getLookup().setCurrentItem(showMsg);
		myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
		myFixture.checkResult("procedure OnTick; begin Actions.ShowMsg(<caret>) end;");
	}

	public void testDeprecatedMemberIsStruckOut()
	{
		myFixture.configureByText("a.script", "procedure OnTick; begin Actions.GiveUnit<caret> end;");
		myFixture.completeBasic();
		LookupElement[] elements = myFixture.getLookupElements();
		assertNotNull(elements);
		boolean giveUnit = false;
		boolean giveUnitEx = false;
		for (LookupElement element : elements) {
			LookupElementPresentation presentation = new LookupElementPresentation();
			element.renderElement(presentation);
			if (element.getLookupString().equals("GiveUnit")) {
				giveUnit = true;
				assertTrue(presentation.isStrikeout());
			}
			if (element.getLookupString().equals("GiveUnitEx")) {
				giveUnitEx = true;
				assertFalse(presentation.isStrikeout());
			}
		}
		assertTrue(giveUnit && giveUnitEx);
	}

	public void testScopeInStatementPosition()
	{
		List<String> items = complete("var Counter: Integer; type TC = (cA, cB);\nprocedure Helper; begin end;\nprocedure P(aX: Integer); var Local: Boolean; begin <caret> end;");
		assertContainsElements(items, "Counter", "Local", "aX", "Helper", "P", "cA", "Actions", "A", "States", "Utils", "utSerf", "begin", "if", "exit");
		assertDoesntContain(items, "TKMScriptActions", "OnTick", "true");
	}

	public void testExpressionAndTypePositions()
	{
		assertContainsElements(complete("procedure P; var x: Boolean; begin x := <caret>; end;"), "true", "nil", "not", "x", "Actions");
		// PascalScript's standard routines come from System.script; Delphi routines the game lacks are not offered
		List<String> standard = complete("procedure P; var i: Integer; begin <caret> end;");
		assertContainsElements(standard, "Inc", "Dec", "Length", "SetLength", "High", "IntToStr", "StrToIntDef", "Copy", "Trim", "Round", "Pi", "Ord", "Chr");
		assertDoesntContain(standard, "Random", "Format", "Min");
		List<String> types = complete("var x: <caret>;");
		assertContainsElements(types, "Integer", "Boolean", "TKMPoint", "TKMUnitType", "array", "record", "set");
		assertDoesntContain(types, "Actions", "utSerf", "begin");
	}

	public void testFunctionResult()
	{
		assertContainsElements(complete("function F: Integer; begin <caret> end;"), "Result", "F");
	}

	public void testEventTemplateAtTopLevel()
	{
		List<String> items = complete("procedure OnTick;\nbegin\nend;\n\nOn<caret>");
		assertContainsElements(items, "OnHouseBuilt", "OnMissionStart");
		assertDoesntContain(items, "OnTick"); // already implemented

		myFixture.configureByText("a.script", "procedure OnTick;\nbegin\nend;\n\nOnHouseB<caret>");
		if (myFixture.completeBasic() != null) {
			myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
		}
		KmrPascalEvent event = KmrPascalEvents.getInstance(getProject()).findByHandlerName("r16020", "OnHouseBuilt");
		assertNotNull(event);
		myFixture.checkResult("procedure OnTick;\nbegin\nend;\n\nprocedure OnHouseBuilt" + event.getSignature().getSignatureText(false) + ";\nbegin\n  <caret>\nend;");
	}

	public void testEventTemplateAfterProcedureKeyword()
	{
		myFixture.configureByText("a.script", "procedure OnMissionSt<caret>");
		if (myFixture.completeBasic() != null) {
			myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
		}
		myFixture.checkResult("procedure OnMissionStart;\nbegin\n  <caret>\nend;");
	}

	public void testDirectiveNameCompletion()
	{
		List<String> items = complete("{$<caret>}");
		assertContainsElements(items, "I", "INCLUDE", "DEFINE", "UNDEF", "IFDEF", "IFNDEF", "ELSE", "ENDIF", "EVENT", "COMMAND", "CMD",
			"CUSTOM_TH_TROOP_COST", "CUSTOM_MARKET_GOLD_PRICE_X");
		assertDoesntContain(complete("{$I<caret>}"), "DEFINE", "EVENT");

		// a directive with an argument gets the separating space, one without is closed and left behind
		myFixture.configureByText("a.script", "{$EV<caret>}");
		myFixture.completeBasic();
		myFixture.checkResult("{$EVENT <caret>}");
		myFixture.configureByText("a.script", "{$ENDI<caret>");
		myFixture.completeBasic();
		myFixture.checkResult("{$ENDIF}<caret>");
		// an unterminated directive is closed as well
		myFixture.configureByText("a.script", "{$INCLUD<caret>");
		myFixture.completeBasic();
		myFixture.checkResult("{$INCLUDE <caret>}");
	}

	public void testEventNameCompletionInDirective()
	{
		List<String> items = complete("{$EVENT <caret>}");
		assertContainsElements(items, "evtHouseBuilt", "evtMissionStart", "evtTick");
		assertDoesntContain(items, "OnHouseBuilt", "procedure");

		myFixture.configureByText("a.script", "{$EVENT evtHousePlanDig<caret>}");
		myFixture.completeBasic();
		myFixture.checkResult("{$EVENT evtHousePlanDigged:<caret>}");
	}

	public void testHandlerCompletionInDirective()
	{
		String unit = """
			procedure MyBuilt(aHouse: Integer);
			begin
			end;
			procedure Whatever(aText: String);
			begin
			end;
			function Counter: Integer;
			begin
			  Result := 0;
			end;
			procedure OnTick;
			begin
			end;
			""";
		List<String> items = complete(unit + "{$EVENT evtHouseBuilt:<caret>}");
		assertContainsElements(items, "MyBuilt", "Whatever");
		// the signature of the event first; functions and procedures that already handle an event are not offered
		assertTrue(items.toString(), items.indexOf("MyBuilt") < items.indexOf("Whatever"));
		assertDoesntContain(items, "Counter", "OnTick");

		// a procedure another directive already registered would be a duplicate for the game
		assertDoesntContain(complete(unit + "{$EVENT evtHousePlanDigged:MyBuilt}\n{$EVENT evtHouseBuilt:<caret>}"), "MyBuilt");
		// but the handler of the directive being edited is completable again
		assertContainsElements(complete(unit + "{$EVENT evtHouseBuilt:MyBu<caret>ilt}"), "MyBuilt");

		myFixture.configureByText("a.script", unit + "{$EVENT evtHouseBuilt:MyBui<caret>}");
		myFixture.completeBasic();
		myFixture.checkResult(unit + "{$EVENT evtHouseBuilt:MyBuilt}<caret>");
	}

	public void testTopLevelKeywordsAndLocalDeclarationKeywords()
	{
		assertContainsElements(complete("<caret>"), "procedure", "function", "var", "const", "type", "OnTick");
		assertContainsElements(complete("procedure P;\n<caret>\nbegin end;"), "var", "const", "begin");
	}

	public void testVersionFiltersCompletion()
	{
		assertContainsElements(complete("procedure P; begin Actions.<caret> end;"), "GiveUnitEx");
		KmrPascalProjectSettings.getInstance(getProject()).setDefaultStubVersion("r6720");
		PsiManager.getInstance(getProject()).dropPsiCaches();
		try {
			List<String> items = complete("procedure P; begin Actions.<caret> end;");
			assertContainsElements(items, "GiveUnit", "ShowMsg");
			assertDoesntContain(items, "GiveUnitEx");
			assertDoesntContain(complete("procedure P; begin <caret> end;"), "Utils", "U");
		} finally {
			KmrPascalProjectSettings.getInstance(getProject()).setDefaultStubVersion("r16020");
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// parameter info
	// ---------------------------------------------------------------------------------------------------------------

	public void testParameterInfo()
	{
		myFixture.configureByText("a.script", "procedure P; begin Actions.ShowMsg(0, <caret>'x'); end;");
		KmrPascalParameterInfoHandler handler = new KmrPascalParameterInfoHandler();

		MockCreateParameterInfoContext create = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
		PsiElement call = handler.findElementForParameterInfo(create);
		assertInstanceOf(call, KmrPascalCallExpression.class);
		assertEquals(1, create.getItemsToShow().length);
		KmrPascalCallable callable = (KmrPascalCallable) create.getItemsToShow()[0];
		assertEquals("ShowMsg", callable.getName());

		MockUpdateParameterInfoContext update = new MockUpdateParameterInfoContext(myFixture.getEditor(), myFixture.getFile(), create.getItemsToShow());
		PsiElement forUpdate = handler.findElementForUpdatingParameterInfo(update);
		assertNotNull(forUpdate);
		handler.updateParameterInfo(forUpdate, update);
		assertEquals(1, update.getCurrentParameter());

		MockParameterInfoUIContext<PsiElement> ui = new MockParameterInfoUIContext<>(call);
		ui.setCurrentParameterIndex(1);
		handler.updateUI(callable, ui);
		assertTrue(ui.getText(), ui.getText().startsWith("aHand: ") && ui.getText().contains(", aText: "));
		assertEquals("aText", ui.getText().substring(ui.getHighlightStart(), ui.getHighlightStart() + 5));
	}

	public void testParameterInfoInUnfinishedAndNestedCalls()
	{
		myFixture.configureByText("a.script", "procedure P; begin Actions.ShowMsg(0, <caret>\nend;");
		KmrPascalParameterInfoHandler handler = new KmrPascalParameterInfoHandler();
		MockCreateParameterInfoContext unfinished = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
		PsiElement owner = handler.findElementForParameterInfo(unfinished);
		assertNotNull(owner);
		assertEquals("ShowMsg", ((KmrPascalCallable) unfinished.getItemsToShow()[0]).getName());
		MockUpdateParameterInfoContext unfinishedUpdate = new MockUpdateParameterInfoContext(myFixture.getEditor(), myFixture.getFile(), unfinished.getItemsToShow());
		handler.updateParameterInfo(owner, unfinishedUpdate);
		assertEquals(1, unfinishedUpdate.getCurrentParameter());
		// outside any call: nothing
		myFixture.configureByText("a.script", "procedure P; begin x := 1; <caret>\nend;");
		assertNull(handler.findElementForParameterInfo(new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile())));

		myFixture.configureByText("a.script", """
			function Inner(a, b: Integer): Integer; begin Result := a; end;
			procedure Outer(x: Integer; const y: Integer); begin end;
			procedure P; begin Outer(Inner(1, <caret>2), 3); end;
			""");
		MockCreateParameterInfoContext create = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
		PsiElement call = handler.findElementForParameterInfo(create);
		assertNotNull(call);
		assertEquals("Inner", ((KmrPascalCallable) create.getItemsToShow()[0]).getName());
		MockUpdateParameterInfoContext update = new MockUpdateParameterInfoContext(myFixture.getEditor(), myFixture.getFile(), create.getItemsToShow());
		handler.updateParameterInfo(call, update);
		assertEquals(1, update.getCurrentParameter());

		// modifiers are shown
		myFixture.configureByText("a.script", "procedure Outer(x: Integer; const y: Integer); begin end;\nprocedure P; begin Outer(1, <caret>2); end;");
		create = new MockCreateParameterInfoContext(myFixture.getEditor(), myFixture.getFile());
		call = handler.findElementForParameterInfo(create);
		MockParameterInfoUIContext<PsiElement> ui = new MockParameterInfoUIContext<>(call);
		ui.setCurrentParameterIndex(1);
		handler.updateUI((KmrPascalCallable) create.getItemsToShow()[0], ui);
		assertEquals("x: Integer, const y: Integer", ui.getText());
	}

	// ---------------------------------------------------------------------------------------------------------------
	// documentation
	// ---------------------------------------------------------------------------------------------------------------

	public void testQuickDocumentation()
	{
		KmrPascalDocumentationProvider provider = new KmrPascalDocumentationProvider();

		PsiElement showMsg = resolve("procedure P; begin Actions.Show<caret>Msg(0, ''); end;");
		String doc = provider.generateDoc(showMsg, null);
		assertNotNull(doc);
		assertTrue(doc, doc.contains("procedure Actions.ShowMsg(") && doc.contains("Available since") && doc.contains("KaM Remake API (r16020)"));
		String navigate = provider.getQuickNavigateInfo(showMsg, null);
		assertTrue(navigate, navigate.startsWith("procedure Actions.ShowMsg("));

		String deprecated = provider.generateDoc(resolve("procedure P; begin Actions.Give<caret>Unit(0, 1, 1, 1, 0); end;"), null);
		assertTrue(deprecated, deprecated.contains("Deprecated") && deprecated.contains("GiveUnitEx"));

		String user = provider.generateDoc(resolve("// how many things happened\n// @see Other\nvar Counter: Integer;\nprocedure P; begin Coun<caret>ter := 1; end;"), null);
		assertTrue(user, user.contains("var Counter: Integer") && user.contains("how many things happened") && user.contains("See also") && user.contains("a.script"));

		String field = provider.generateDoc(resolve("procedure P; begin x := States.HousePosition(1).<caret>X; end;"), null);
		assertTrue(field, field.contains("field TKMPoint.X: Integer"));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// status bar
	// ---------------------------------------------------------------------------------------------------------------

	public void testStatusBarVersion()
	{
		myFixture.configureByText("a.script", "procedure P; begin end;");
		assertEquals("r16020", KmrPascalVersionStatusBarWidget.effectiveVersion(getProject(), myFixture.getFile().getVirtualFile()));
		assertEquals("KMR: r16020", KmrPascalVersionStatusBarWidget.widgetText("r16020"));
		try {
			KmrPascalVersionStatusBarWidget.setDefaultVersion(getProject(), "r6720");
			assertEquals("r6720", KmrPascalVersionStatusBarWidget.effectiveVersion(getProject(), myFixture.getFile().getVirtualFile()));
			// a per-map override wins over the default
			myFixture.addFileToProject("map.dat", "");
			var entry = myFixture.addFileToProject("map.script", "procedure P; begin end;").getVirtualFile();
			KmrPascalProjectSettings.getInstance(getProject()).setStubVersionOverride(entry, "r16020");
			PsiManager.getInstance(getProject()).dropPsiCaches();
			assertEquals("r16020", KmrPascalVersionStatusBarWidget.effectiveVersion(getProject(), entry));
		} finally {
			KmrPascalVersionStatusBarWidget.setDefaultVersion(getProject(), "r16020");
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	private List<String> complete(String text)
	{
		myFixture.configureByText("a.script", text);
		myFixture.completeBasic();
		List<String> items = myFixture.getLookupElementStrings();
		assertNotNull("completion auto-inserted a single item for: " + text, items);
		return items;
	}

	private PsiElement resolve(String text)
	{
		myFixture.configureByText("a.script", text);
		PsiReference reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
		assertNotNull(reference);
		PsiElement target = reference.resolve();
		assertNotNull("unresolved: " + text, target);
		return target;
	}

}
