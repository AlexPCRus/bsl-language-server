/*
 * This file is a part of BSL Language Server.
 *
 * Copyright © 2018-2020
 * Alexey Sosnoviy <labotamy@gmail.com>, Nikita Gryzlov <nixel2007@gmail.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * BSL Language Server is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * BSL Language Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BSL Language Server.
 */
package com.github._1c_syntax.bsl.languageserver.diagnostics;

import com.github._1c_syntax.bsl.languageserver.context.symbol.MethodSymbol;
import com.github._1c_syntax.bsl.languageserver.context.symbol.RegionSymbol;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticCompatibilityMode;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticInfo;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticMetadata;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticScope;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticSeverity;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticTag;
import com.github._1c_syntax.bsl.languageserver.diagnostics.metadata.DiagnosticType;
import com.github._1c_syntax.bsl.languageserver.utils.Ranges;
import com.github._1c_syntax.bsl.languageserver.utils.Trees;
import com.github._1c_syntax.bsl.parser.BSLParser;
import com.github._1c_syntax.bsl.parser.BSLParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Tree;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;

@DiagnosticMetadata(
  type = DiagnosticType.CODE_SMELL,
  severity = DiagnosticSeverity.INFO,
  scope = DiagnosticScope.BSL,
  minutesToFix = 1,
  tags = {
    DiagnosticTag.STANDARD
  },
  compatibilityMode = DiagnosticCompatibilityMode.COMPATIBILITY_MODE_8_3_1
)
public class CodeOutOfRegionDiagnostic extends AbstractVisitorDiagnostic {
  private List<Range> regionsRanges = new ArrayList<>();

  public CodeOutOfRegionDiagnostic(DiagnosticInfo info) {
    super(info);
  }

  @Override
  public ParseTree visitFile(BSLParser.FileContext ctx) {
    List<RegionSymbol> regions = documentContext.getFileLevelRegions();
    regionsRanges.clear();

    // если областей нет, то и смысла дальше анализировть тоже нет
    if (regions.isEmpty() && !ctx.getTokens().isEmpty()) {
      diagnosticStorage.addDiagnostic(ctx);
      return ctx;
    }

    regions.forEach(region ->
      regionsRanges.add(Ranges.create(region))
    );

    return super.visitFile(ctx);

  }

  @Override
  public ParseTree visitModuleVar(BSLParser.ModuleVarContext ctx) {
    Trees.getChildren(ctx).stream()
      .filter(node -> !(node instanceof BSLParser.PreprocessorContext)
        && !(node instanceof TerminalNode))
      .findFirst()
      .ifPresent((Tree node) -> {
          Range ctxRange = Ranges.create((BSLParserRuleContext) node);
          if (regionsRanges.stream().noneMatch(regionRange ->
            Ranges.containsRange(regionRange, ctxRange))) {
            diagnosticStorage.addDiagnostic(ctx);
          }
        }
      );
    return ctx;
  }

  @Override
  public ParseTree visitSub(BSLParser.SubContext ctx) {
    documentContext.getMethodSymbol(ctx).ifPresent((MethodSymbol methodSymbol) -> {
      if (methodSymbol.getRegion().isEmpty()) {
        diagnosticStorage.addDiagnostic(methodSymbol.getSubNameRange());
      }
    });
    return ctx;
  }

  @Override
  public ParseTree visitFileCodeBlock(BSLParser.FileCodeBlockContext ctx) {
    addDiagnosticForFileCodeBlock(ctx);
    return ctx;
  }

  @Override
  public ParseTree visitFileCodeBlockBeforeSub(BSLParser.FileCodeBlockBeforeSubContext ctx) {
    addDiagnosticForFileCodeBlock(ctx);
    return ctx;
  }

  private void addDiagnosticForFileCodeBlock(BSLParserRuleContext ctx) {
    Trees.findAllRuleNodes(ctx, BSLParser.RULE_statement)
      .forEach((ParseTree child) -> {
        if (child.getParent() instanceof BSLParser.CodeBlockContext
          && Trees.findAllRuleNodes(child, BSLParser.RULE_preprocessor).isEmpty()) {

          Range ctxRange = Ranges.create((BSLParser.StatementContext) child);
          if (regionsRanges.stream().noneMatch(regionRange ->
            Ranges.containsRange(regionRange, ctxRange))) {
            diagnosticStorage.addDiagnostic((BSLParser.StatementContext) child);
          }
        }
      });
  }
}