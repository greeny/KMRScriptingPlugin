package dev.greeny.kmr.language;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** Parameter name hints ({@code Foo(aX: 1)}) and when they are left out. */
public class KmrPascalInlayHintsTest extends BasePlatformTestCase
{

	public void testHintsForLiteralsAndDifferentlyNamedArguments()
	{
		check("procedure Reward(aPlayer, aAmount: Integer; aSilent: Boolean); begin end;\n"
			+ "procedure P; var Gold: Integer; begin Reward(<hint text=\"aPlayer:\"/>0, <hint text=\"aAmount:\"/>Gold, <hint text=\"aSilent:\"/>True); end;");
	}

	public void testApiCall()
	{
		check("procedure OnTick; begin Actions.ShowMsg(<hint text=\"aHand:\"/>0, <hint text=\"aText:\"/>'Hi'); end;");
	}

	public void testNoHintWhenArgumentIsNamedLikeTheParameter()
	{
		check("procedure Reward(aPlayer, aAmount: Integer); begin end;\n"
			+ "procedure OnPlayerVictory(aPlayer: Integer); var Amount, MyAmount: Integer; begin\n"
			+ "  Reward(aPlayer, Amount);\n"
			+ "  Reward(<hint text=\"aPlayer:\"/>1, MyAmount);\n"
			+ "end;");
	}

	public void testNoHintForCallOrFieldNamedLikeTheParameter()
	{
		check("procedure Give(aUnitType: TKMUnitType; aX: Integer); begin end;\n"
			+ "procedure P(aUnitID: Integer); var Pos: TKMPoint; begin Give(States.UnitType(aUnitID), Pos.X); end;");
	}

	public void testNoHintForSingleParameterContainedInRoutineName()
	{
		check("procedure KillPlayer(aPlayer: Integer); begin end;\n"
			+ "procedure OnTick; begin KillPlayer(0); Actions.PlayerDefeat(<hint text=\"aHand:\"/>0); end;");
	}

	public void testNoHintsWhenArgumentCountIsWrong()
	{
		check("procedure Reward(aPlayer, aAmount: Integer); begin end;\n"
			+ "procedure P; begin Reward(0); Unknown(1, 2); end;");
	}

	private void check(String text)
	{
		myFixture.configureByText("a.script", text);
		myFixture.testInlays();
	}

}
