package dev.greeny.kmr.language;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;
import dev.greeny.kmr.language.psi.KmrPascalTypes;

%%

%class KmrPascalLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{  return;
%eof}

%state STRING
%state COMMENT_A
%state COMMENT_B
%state DIRECTIVE

%caseless

%{
  /** token type to return when the current {$...} directive closes; set by the rule that entered DIRECTIVE state */
  private IElementType directiveType = KmrPascalTypes.DIRECTIVE;
%}

DIGIT      = [0-9]
HEX_DIGIT  = [0-9a-fA-F]
EXPONENT   = [eE][+-]?{DIGIT}+
IDENTIFIER = [a-zA-Z_][a-zA-Z0-9_]*

%%

// Note: rules that do not return a token type keep lexing, and FlexAdapter merges the consumed text into the
// next token that IS returned. That is how the STRING / COMMENT / DIRECTIVE states produce a single token.

<YYINITIAL> {

  // Keywords
  "begin"        { return KmrPascalTypes.BEGIN; }
  "end"          { return KmrPascalTypes.END; }
  "if"           { return KmrPascalTypes.IF; }
  "else"         { return KmrPascalTypes.ELSE; }
  "with"         { return KmrPascalTypes.WITH; }
  "while"        { return KmrPascalTypes.WHILE; }
  "repeat"       { return KmrPascalTypes.REPEAT; }
  "until"        { return KmrPascalTypes.UNTIL; }
  "for"          { return KmrPascalTypes.FOR; }
  "do"           { return KmrPascalTypes.DO; }
  "then"         { return KmrPascalTypes.THEN; }
  "type"         { return KmrPascalTypes.TYPE; }
  "var"          { return KmrPascalTypes.VAR; }
  "const"        { return KmrPascalTypes.CONST; }
  "procedure"    { return KmrPascalTypes.PROCEDURE; }
  "function"     { return KmrPascalTypes.FUNCTION; }
  "out"          { return KmrPascalTypes.OUT; }
  "to"           { return KmrPascalTypes.TO; }
  "downto"       { return KmrPascalTypes.DOWNTO; }
  "and"          { return KmrPascalTypes.AND; }
  "or"           { return KmrPascalTypes.OR; }
  "not"          { return KmrPascalTypes.NOT; }
  "div"          { return KmrPascalTypes.DIV; }
  "mod"          { return KmrPascalTypes.MOD; }
  "xor"          { return KmrPascalTypes.XOR; }
  "shl"          { return KmrPascalTypes.SHL; }
  "shr"          { return KmrPascalTypes.SHR; }
  "record"       { return KmrPascalTypes.RECORD; }
  "array"        { return KmrPascalTypes.ARRAY; }
  "case"         { return KmrPascalTypes.CASE; }
  "of"           { return KmrPascalTypes.OF; }
  "in"           { return KmrPascalTypes.IN; }
  "nil"          { return KmrPascalTypes.NIL; }
  "exit"         { return KmrPascalTypes.EXIT; }
  "break"        { return KmrPascalTypes.BREAK; }
  "continue"     { return KmrPascalTypes.CONTINUE; }
  "set"          { return KmrPascalTypes.SET; }
  "true"         { return KmrPascalTypes.TRUE; }
  "false"        { return KmrPascalTypes.FALSE; }

  "@"            { return KmrPascalTypes.AT; }

  ":"            { return KmrPascalTypes.COLON; }
  ";"            { return KmrPascalTypes.SEMI; }
  "."            { return KmrPascalTypes.DOT; }
  ","            { return KmrPascalTypes.COMMA; }
  ".."           { return KmrPascalTypes.DOUBLEDOT; }

  // Operators
  "+"            { return KmrPascalTypes.PLUS; }
  "-"            { return KmrPascalTypes.MINUS; }
  "*"            { return KmrPascalTypes.TIMES; }
  "/"            { return KmrPascalTypes.DIVIDE; }
  ":="           { return KmrPascalTypes.ASSIGN; }
  "<>"           { return KmrPascalTypes.NOTEQ; }
  "="            { return KmrPascalTypes.EQ; }
  "<"            { return KmrPascalTypes.LT; }
  ">"            { return KmrPascalTypes.GT; }
  "<="           { return KmrPascalTypes.LE; }
  ">="           { return KmrPascalTypes.GE; }

  "("            { return KmrPascalTypes.LBRACKET; }
  ")"            { return KmrPascalTypes.RBRACKET; }
  "["            { return KmrPascalTypes.LSQUAREBRACKET; }
  "]"            { return KmrPascalTypes.RSQUAREBRACKET; }

  // Numbers: integer, real (with optional exponent), hexadecimal ($FF)
  {DIGIT}+ ("." {DIGIT}+)? {EXPONENT}?  { return KmrPascalTypes.NUMBER; }
  "$" {HEX_DIGIT}+                      { return KmrPascalTypes.NUMBER; }

  // Character literals: #13, #$0D
  "#" {DIGIT}+                          { return KmrPascalTypes.CHAR; }
  "#$" {HEX_DIGIT}+                     { return KmrPascalTypes.CHAR; }

  // Compiler directives {$...}; the kind is decided on entry, the token is returned when "}" closes it
  "{$" ("i" | "include") [ \t]  { directiveType = KmrPascalTypes.DIRECTIVE_INCLUDE; yybegin(DIRECTIVE); }
  "{$define" [ \t]              { directiveType = KmrPascalTypes.DIRECTIVE_DEFINE;  yybegin(DIRECTIVE); }
  "{$undef" [ \t]               { directiveType = KmrPascalTypes.DIRECTIVE_UNDEF;   yybegin(DIRECTIVE); }
  "{$ifdef" [ \t]               { directiveType = KmrPascalTypes.DIRECTIVE_IFDEF;   yybegin(DIRECTIVE); }
  "{$ifndef" [ \t]              { directiveType = KmrPascalTypes.DIRECTIVE_IFNDEF;  yybegin(DIRECTIVE); }
  "{$event" [ \t]               { directiveType = KmrPascalTypes.DIRECTIVE_EVENT;   yybegin(DIRECTIVE); }
  "{$else" / [ \t}]             { directiveType = KmrPascalTypes.DIRECTIVE_ELSE;    yybegin(DIRECTIVE); }
  "{$endif" / [ \t}]            { directiveType = KmrPascalTypes.DIRECTIVE_ENDIF;   yybegin(DIRECTIVE); }
  "{$"                          { directiveType = KmrPascalTypes.DIRECTIVE;         yybegin(DIRECTIVE); }

  // Comments
  "{"            { yybegin(COMMENT_A); }
  "(*"           { yybegin(COMMENT_B); }
  "//"[^\r\n]*   { return KmrPascalTypes.COMMENT_A; }

  "'"            { yybegin(STRING); }

  {IDENTIFIER}   { return KmrPascalTypes.IDENTIFIER; }

  \s+            { return TokenType.WHITE_SPACE; }

  [^]            { return TokenType.BAD_CHARACTER; }
}

<COMMENT_A> {
  "}"            { yybegin(YYINITIAL); return KmrPascalTypes.COMMENT_A; }
  <<EOF>>        { yybegin(YYINITIAL); return KmrPascalTypes.COMMENT_A; }   // unterminated, reported by the annotator
  [^]            { }
}

<COMMENT_B> {
  "*)"           { yybegin(YYINITIAL); return KmrPascalTypes.COMMENT_B; }
  <<EOF>>        { yybegin(YYINITIAL); return KmrPascalTypes.COMMENT_B; }   // unterminated, reported by the annotator
  [^]            { }
}

<DIRECTIVE> {
  "}"            { yybegin(YYINITIAL); return directiveType; }
  [\r\n]         { yypushback(1); yybegin(YYINITIAL); return directiveType; }   // unterminated
  <<EOF>>        { yybegin(YYINITIAL); return directiveType; }                  // unterminated
  [^]            { }
}

<STRING> {
  "''"           { }                                                                       // escaped quote
  "'"            { yybegin(YYINITIAL); return KmrPascalTypes.STRING; }
  [\r\n]         { yypushback(1); yybegin(YYINITIAL); return KmrPascalTypes.STRING; }      // unterminated
  <<EOF>>        { yybegin(YYINITIAL); return KmrPascalTypes.STRING; }                     // unterminated
  [^]            { }
}
