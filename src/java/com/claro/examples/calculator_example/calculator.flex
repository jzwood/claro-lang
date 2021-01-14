/*
 * Copyright (C) 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.claro.examples.calculator_example;

import java_cup.runtime.Symbol;

/**
 * A simple lexer/parser for basic arithmetic expressions.
 *
 * @author Jason Steving, derived from a simpler example by Régis Décamps
 */

%%


%public
%class CalculatorLexer
// Use CUP compatibility mode to interface with a CUP parser.
%cup

%unicode

%{
    // This will be used to accumulate all string characters during the STRING state.
    StringBuffer string = new StringBuffer();

    /** Creates a new {@link Symbol} of the given type. */
    private Symbol symbol(int type) {
        return new Symbol(type, yyline, yycolumn);
    }

    /** Creates a new {@link Symbol} of the given type and value. */
    private Symbol symbol(int type, Object value) {
        return new Symbol(type, yyline, yycolumn, value);
    }
%}

// A (integer) number is a sequence of digits.
Integer        = [0-9]+

// A (decimal) float is a real number with a decimal value.
Float          = {Integer}\.{Integer}

// A variable identifier. We'll just do uppercase for vars.
Identifier     = [A-Z]+

// A line terminator is a \r (carriage return), \n (line feed), or \r\n. */
LineTerminator = \r|\n|\r\n

/* White space is a line terminator, space, tab, or line feed. */
WhiteSpace     = {LineTerminator} | [ \t\f]

%state LINECOMMENT
%state STRING

%%

// This section contains regular expressions and actions, i.e. Java code, that will be executed when
// the scanner matches the associated regular expression.


// YYINITIAL is the initial state at which the lexer begins scanning.
<YYINITIAL> {

    /* Create a new parser symbol for the lexem. */
    "+"                { return symbol(Calc.PLUS); }
    "-"                { return symbol(Calc.MINUS); }
    "*"                { return symbol(Calc.MULTIPLY); }
    "^"                { return symbol(Calc.EXPONENTIATE); }
    "/"                { return symbol(Calc.DIVIDE); }
    "("                { return symbol(Calc.LPAR); }
    ")"                { return symbol(Calc.RPAR); }
    "=="               { return symbol(Calc.EQUALS); }
    "="                { return symbol(Calc.ASSIGNMENT); }
    ";"                { return symbol(Calc.SEMICOLON); }
    "log_"             { return symbol(Calc.LOG_PREFIX); }
    "print"            { return symbol(Calc.PRINT); }
    "numeric_bool"     { return symbol(Calc.NUMERIC_BOOL); }
    "input"            { return symbol(Calc.INPUT); }
    \"                 {
                         // There may have already been another string accumulated into this buffer.
                         // In that case we need to clear the buffer to start processing this.
                         string.setLength(0);
                         yybegin(STRING);
                       }

    // If the line comment symbol is found, ignore the token and then switch to the LINECOMMENT lexer state.
    "#"                { yybegin(LINECOMMENT); }

    // If an integer is found, return the token INTEGER that represents an integer and the value of
    // the integer that is held in the string yytext
    {Integer}          { return symbol(Calc.INTEGER, Double.parseDouble(yytext())); }

    // If float is found, return the token FLOAT that represents a float and the value of
    // the float that is held in the string yytext
    {Float}            { return symbol(Calc.FLOAT, Double.parseDouble(yytext())); }

    {Identifier}       { return symbol(Calc.IDENTIFIER, yytext()); }

    /* Don't do anything if whitespace is found */
    {WhiteSpace}       { /* do nothing with space */ }
}

// A comment that goes all the way from the symbol '#' to the end of the line or EOF.
<LINECOMMENT> {
    {LineTerminator}   { yybegin(YYINITIAL); }
    .                  { /* Ignore everything in the rest of the commented line. */ }
}

// A String is a sequence of any printable characters between quotes.
<STRING> {
    \"                 {
                          yybegin(YYINITIAL);
                          return symbol(Calc.STRING, string.toString());
                       }
    [^\n\r\"\\]+       { string.append( yytext() ); }
    \\t                { string.append('\t'); }
    \\n                { string.append('\n'); }

    \\r                { string.append('\r'); }
    \\\"               { string.append('\"'); }
    \\                 { string.append('\\'); }
}

// We have changed the default symbol in the bazel `cup()` rule from "sym" to "Calc", so we need to
// change how JFlex handles the end of file.
// See http://jflex.de/manual.html#custom-symbol-interface
<<EOF>>                { return symbol(Calc.EOF); }

/* Catch-all the rest, i.e. unknown character. */
[^]  { throw new CalculatorParserException("Illegal character <" + yytext() + ">"); }
