package dev.greeny.kmr.language;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.greeny.kmr.language.psi.*;
import dev.greeny.kmr.language.resolve.KmrPascalCallable;
import dev.greeny.kmr.language.stubs.*;
import dev.greeny.kmr.language.types.KmrTypePresenter;
import dev.greeny.kmr.language.unit.KmrPascalProjectSettings;

import java.util.List;

/** API stubs: loading, resolution from user code, hiding, versions, events, doc tags. */
public class KmrPascalStubTest extends BasePlatformTestCase
{

	public void testEveryShippedStubParsesWithoutErrors()
	{
		KmrPascalStubLibrary library = KmrPascalStubLibrary.getInstance(getProject());
		for (String version : KmrPascalStubLibrary.VERSIONS) {
			KmrPascalStubLibrary.StubSet set = library.getStubSet(version);
			assertFalse(version + " has no scope files", set.scopeFiles.isEmpty());
			assertNotNull(version + " has no Events.script", set.eventsFile);
			for (PsiFile file : set.scopePsiFiles(getProject())) {
				assertEmpty(version + "/" + file.getName() + ": " + describe(file), PsiTreeUtil.collectElementsOfType(file, PsiErrorElement.class));
				assertFalse(file.getVirtualFile().isWritable());
			}
			PsiFile events = set.eventsPsiFile(getProject());
			assertNotNull(events);
			assertEmpty(PsiTreeUtil.collectElementsOfType(events, PsiErrorElement.class));
			assertFalse(KmrPascalEvents.getInstance(getProject()).getEvents(version).isEmpty());
		}
	}

	public void testStandardLibraryStub()
	{
		// System.script joins the scope of every version, with the same content
		for (String version : KmrPascalStubLibrary.VERSIONS) {
			KmrPascalStubLibrary.StubSet set = KmrPascalStubLibrary.getInstance(getProject()).getStubSet(version);
			assertTrue(version, set.scopePsiFiles(getProject()).stream().anyMatch(KmrPascalStubLibrary::isSystemStubFile));
		}
		PsiElement inc = resolve("procedure P; var I: Integer; begin In<caret>c(I); end;");
		assertInstanceOf(inc, KmrPascalVarIdentifier.class);
		assertTrue(KmrPascalStubLibrary.isSystemStubFile(inc.getContainingFile()));
		assertEquals("System.script", inc.getContainingFile().getName());
		KmrPascalCallable callable = KmrPascalCallable.of(inc);
		assertNotNull(callable);
		assertFalse(callable.isFunction());
		assertEquals("(var X: Variant)", callable.getSignatureText());

		KmrPascalCallable length = KmrPascalCallable.of(resolve("procedure P; var S: String; begin x := Len<caret>gth(S); end;"));
		assertNotNull(length);
		assertEquals("(S: Variant): Integer", length.getSignatureText());
		assertInstanceOf(resolve("procedure P; begin x := Var<caret>Type(1) = varInteger; end;"), KmrPascalVarIdentifier.class);
		assertInstanceOf(resolve("procedure P; begin x := VarType(1) = var<caret>Integer; end;"), KmrPascalConstantDeclaration.class);
		assertInstanceOf(resolve("procedure P; begin RaiseException(erCustom<caret>Error, 'x'); end;"), KmrPascalEnumValue.class);
		// Ord and Chr are compiler keywords parsed like functions: available
		assertInstanceOf(resolve("procedure P; begin x := O<caret>rd('a'); end;"), KmrPascalVarIdentifier.class);
		assertInstanceOf(resolve("procedure P; begin x := C<caret>hr(65); end;"), KmrPascalVarIdentifier.class);
		// the game has no Random / Format / Min: they must not resolve
		assertNull(resolve("procedure P; begin x := Rand<caret>om(5); end;"));
		assertNull(resolve("procedure P; begin x := M<caret>in(1, 2); end;"));
	}

	public void testApiMemberResolvesIntoStub()
	{
		PsiElement target = resolve("procedure OnTick; begin Actions.Show<caret>Msg(-1, 'hi'); end;");
		assertInstanceOf(target, KmrPascalFieldIdentifier.class);
		assertEquals("ShowMsg", ((PsiNamedElement) target).getName());
		assertEquals("Actions.script", target.getContainingFile().getName());
		assertTrue(KmrPascalStubLibrary.isStubFile(target.getContainingFile()));

		KmrPascalCallable callable = KmrPascalCallable.of(target);
		assertNotNull(callable);
		assertFalse(callable.isFunction());
		assertEquals(2, callable.getParameterCount());
		assertEquals("aText", callable.getParameterIdentifiers().get(1).getName());
		assertTrue(callable.getSignatureText(), callable.getSignatureText().startsWith("(aHand: ") && callable.getSignatureText().contains("; aText: "));
		assertNull(callable.getDeprecationMessage());
	}

	public void testAliasAndFunctionField()
	{
		PsiElement target = resolve("procedure OnTick; var t: Integer; begin t := S.Game<caret>Time; end;");
		assertInstanceOf(target, KmrPascalFieldIdentifier.class);
		KmrPascalCallable callable = KmrPascalCallable.of(target);
		assertNotNull(callable);
		assertTrue(callable.isFunction());
		assertEquals(0, callable.getParameterCount());
		// GameTime is tagged "@kind tickCount": the kind is shown in words after the type
		assertEquals(": Cardinal", callable.getSignatureText(false));
		assertEquals(": Cardinal (tick count)", callable.getSignatureText());
	}

	public void testStubTypesAndEnumValuesAreVisible()
	{
		assertInstanceOf(resolve("var p: TKM<caret>Point;"), KmrPascalTypeDeclaration.class);
		assertInstanceOf(resolve("procedure P; begin if States.UnitType(1) = ut<caret>Serf then Exit; end;"), KmrPascalEnumValue.class);
		// record fields of a stub type through a user variable
		assertInstanceOf(resolve("var pt: TKMPoint; procedure P; begin pt.<caret>X := 1; end;"), KmrPascalFieldIdentifier.class);
		// return type of a stub function used as a record
		assertInstanceOf(resolve("procedure P; begin x := States.HousePosition(1).<caret>Y; end;"), KmrPascalFieldIdentifier.class);
	}

	public void testHiddenTypesAndEventsRecordAreInvisibleToUserCode()
	{
		assertNull(resolve("var a: TKMScript<caret>Actions;"));
		assertNull(resolve("var e: TKMScript<caret>Events;"));
		assertNull(resolve("procedure P; begin On<caret>Tick; end;"));
	}

	public void testUserDeclarationsCannotBreakStubs()
	{
		// a user type with the stub's hidden type name: Actions still resolves inside the stub world
		PsiElement target = resolve("type TKMScriptActions = record Foo: Integer; end; procedure P; begin Actions.Show<caret>Msg(0, ''); end;");
		assertInstanceOf(target, KmrPascalFieldIdentifier.class);
		assertEquals("Actions.script", target.getContainingFile().getName());
	}

	public void testUserDeclarationShadowsStub()
	{
		PsiElement target = resolve("var Actions: Integer; procedure P; begin Act<caret>ions := 1; end;");
		assertInstanceOf(target, KmrPascalVarIdentifier.class);
		assertFalse(KmrPascalStubLibrary.isStubFile(target.getContainingFile()));
	}

	public void testDeprecatedTag()
	{
		KmrPascalCallable callable = KmrPascalCallable.of(resolve("procedure P; begin Actions.Give<caret>Unit(0, 1, 2, 3, 4); end;"));
		assertNotNull(callable);
		assertEquals("Use GiveUnitEx instead", callable.getDeprecationMessage());
	}

	public void testVersionSwitchChangesVisibleApi()
	{
		String text = "procedure P; begin Actions.GiveUnit<caret>Ex(0, utSerf, 1, 1, dirN); end;";
		assertInstanceOf(resolve(text), KmrPascalFieldIdentifier.class);

		KmrPascalProjectSettings.getInstance(getProject()).setDefaultStubVersion("r6720");
		PsiManager.getInstance(getProject()).dropPsiCaches();
		try {
			// GiveUnitEx is r14000+, GiveUnit and ShowMsg exist since r5057
			assertNull(resolve(text));
			assertInstanceOf(resolve("procedure P; begin Actions.Give<caret>Unit(0, 1, 1, 1, 0); end;"), KmrPascalFieldIdentifier.class);
			assertInstanceOf(resolve("procedure P; begin Actions.Show<caret>Msg(0, ''); end;"), KmrPascalFieldIdentifier.class);
			assertNull(resolve("procedure P; begin x := Utils.Abs<caret>I(1); end;"));
			KmrPascalEvents events = KmrPascalEvents.getInstance(getProject());
			assertTrue(events.getEvents("r6720").size() < events.getEvents("r16020").size());
			assertNotNull(events.findByHandlerName("r6720", "OnHouseBuilt"));
			assertNull(events.findByHandlerName("r6720", "OnHouseAfterDestroyedEx"));
		} finally {
			KmrPascalProjectSettings.getInstance(getProject()).setDefaultStubVersion("r16020");
		}
	}

	public void testEvents()
	{
		KmrPascalEvents events = KmrPascalEvents.getInstance(getProject());
		KmrPascalEvent houseBuilt = events.findByHandlerName("r16020", "onhousebuilt");
		assertNotNull(houseBuilt);
		assertEquals("evtHouseBuilt", houseBuilt.eventName);
		assertEquals(1, houseBuilt.getSignature().getParameterCount());
		assertEquals("Integer", houseBuilt.getSignature().getParameterDeclarations().get(0).getTypeSpec().getText());
		assertEquals("(aHouse: Integer (house ID))", houseBuilt.getSignature().getSignatureText());
		assertSame(houseBuilt, events.findByEventName("r16020", "EVTHOUSEBUILT"));
		assertNotNull(houseBuilt.doc);
		assertFalse(houseBuilt.doc.getDescription().isEmpty());
		assertEquals("5057", houseBuilt.doc.getTagValue("since"));
		// an event with several parameters keeps their order and names
		KmrPascalEvent unitDied = events.findByHandlerName("r16020", "OnUnitDied");
		assertNotNull(unitDied);
		assertEquals(List.of("aUnit", "aKillerOwner"), unitDied.getSignature().getParameterIdentifiers().stream().map(KmrPascalParameterIdentifier::getName).toList());
		// convention when no @event tag is present
		assertEquals("evtSomething", KmrPascalDocComment.parse("x").hasTag("event") ? "" : "evtSomething");
	}

	public void testEventDirectiveReferences()
	{
		myFixture.configureByText("main.script", """
			{$EVENT evtHouseBuilt:MyHouseBuilt}
			procedure MyHouseBuilt(aHouseID: Integer);
			begin
			end;
			""");
		PsiFile file = myFixture.getFile();
		int eventOffset = file.getText().indexOf("evtHouseBuilt") + 3;
		int handlerOffset = file.getText().indexOf(":MyHouseBuilt") + 3;

		PsiReference eventReference = file.findReferenceAt(eventOffset);
		assertNotNull(eventReference);
		PsiElement event = eventReference.resolve();
		assertInstanceOf(event, KmrPascalFieldIdentifier.class);
		assertEquals("OnHouseBuilt", ((PsiNamedElement) event).getName());

		PsiReference handlerReference = file.findReferenceAt(handlerOffset);
		assertNotNull(handlerReference);
		assertInstanceOf(handlerReference.resolve(), KmrPascalProcedureDeclaration.class);

		// renaming the handler updates the directive
		myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf("procedure MyHouseBuilt") + "procedure My".length());
		myFixture.renameElementAtCaret("HouseDone");
		assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().startsWith("{$EVENT evtHouseBuilt:HouseDone}"));
	}

	public void testDocCommentParsing()
	{
		KmrPascalDocComment doc = KmrPascalDocComment.parse("""
			 Displays a message.
			 Second line.
			 @param aPlayer Player index,
			   -1 for all
			 @param aText Text
			 @deprecated
			 @return nothing """);
		assertEquals("Displays a message.\nSecond line.", doc.getDescription());
		assertEquals(List.of("aPlayer Player index, -1 for all", "aText Text"), doc.getTagValues("PARAM"));
		assertTrue(doc.hasTag("deprecated"));
		assertEquals("", doc.getTagValue("deprecated"));
		assertEquals("nothing", doc.getTagValue("return"));

		PsiFile file = myFixture.configureByText("a.script", """
			// line one
			// @see Other
			procedure P; begin end;

			{ Not attached: blank line follows }

			var X: Integer;
			""");
		KmrPascalProcedureDeclaration p = PsiTreeUtil.findChildOfType(file, KmrPascalProcedureDeclaration.class);
		KmrPascalDocComment pDoc = KmrPascalDocComment.of(p);
		assertNotNull(pDoc);
		assertEquals("line one", pDoc.getDescription());
		assertEquals("Other", pDoc.getTagValue("see"));
		assertNull(KmrPascalDocComment.of(PsiTreeUtil.findChildOfType(file, KmrPascalVarIdentifier.class)));
	}

	// ---------------------------------------------------------------------------------------------------------------

	private PsiElement resolve(String text)
	{
		PsiFile file = myFixture.configureByText("a.script", text);
		PsiReference reference = file.findReferenceAt(myFixture.getCaretOffset());
		assertNotNull("no reference at caret", reference);
		if (reference instanceof PsiPolyVariantReference) {
			assertTrue("ambiguous", ((PsiPolyVariantReference) reference).multiResolve(false).length <= 1);
		}
		return reference.resolve();
	}

	private static String describe(PsiFile file)
	{
		StringBuilder sb = new StringBuilder();
		for (PsiErrorElement error : PsiTreeUtil.collectElementsOfType(file, PsiErrorElement.class)) {
			sb.append(error.getTextOffset()).append(": ").append(error.getErrorDescription()).append("; ");
		}
		return sb.toString();
	}

}
