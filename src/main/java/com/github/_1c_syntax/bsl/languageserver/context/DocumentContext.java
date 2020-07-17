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
package com.github._1c_syntax.bsl.languageserver.context;

import com.github._1c_syntax.bsl.languageserver.context.computer.CognitiveComplexityComputer;
import com.github._1c_syntax.bsl.languageserver.context.computer.ComplexityData;
import com.github._1c_syntax.bsl.languageserver.context.computer.Computer;
import com.github._1c_syntax.bsl.languageserver.context.computer.CyclomaticComplexityComputer;
import com.github._1c_syntax.bsl.languageserver.context.computer.DiagnosticComputer;
import com.github._1c_syntax.bsl.languageserver.context.computer.DiagnosticIgnoranceComputer;
import com.github._1c_syntax.bsl.languageserver.context.computer.SymbolTreeComputer;
import com.github._1c_syntax.bsl.languageserver.context.symbol.MethodSymbol;
import com.github._1c_syntax.bsl.languageserver.context.symbol.SymbolTree;
import com.github._1c_syntax.bsl.languageserver.utils.Trees;
import com.github._1c_syntax.bsl.parser.BSLLexer;
import com.github._1c_syntax.bsl.parser.BSLParser;
import com.github._1c_syntax.bsl.parser.BSLParserRuleContext;
import com.github._1c_syntax.bsl.parser.Tokenizer;
import com.github._1c_syntax.mdclasses.mdo.MDObjectBase;
import com.github._1c_syntax.mdclasses.metadata.SupportConfiguration;
import com.github._1c_syntax.mdclasses.metadata.additional.ModuleType;
import com.github._1c_syntax.mdclasses.metadata.additional.SupportVariant;
import com.github._1c_syntax.utils.Lazy;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.antlr.v4.runtime.tree.Tree;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.antlr.v4.runtime.Token.DEFAULT_CHANNEL;

public class DocumentContext {

  private final URI uri;
  private String content;
  private final ServerContext context;
  private final DiagnosticComputer diagnosticComputer;

  private final FileType fileType;
  private Tokenizer tokenizer;

  private final ReentrantLock computeLock = new ReentrantLock();
  private final ReentrantLock diagnosticsLock = new ReentrantLock();

  private final Lazy<String[]> contentList = new Lazy<>(this::computeContentList, computeLock);
  private final Lazy<ModuleType> moduleType = new Lazy<>(this::computeModuleType, computeLock);
  private final Lazy<Map<SupportConfiguration, SupportVariant>> supportVariants
    = new Lazy<>(this::computeSupportVariants, computeLock);
  private final Lazy<SymbolTree> symbolTree = new Lazy<>(this::computeSymbolTree, computeLock);
  private final Lazy<ComplexityData> cognitiveComplexityData
    = new Lazy<>(this::computeCognitiveComplexity, computeLock);
  private final Lazy<ComplexityData> cyclomaticComplexityData
    = new Lazy<>(this::computeCyclomaticComplexity, computeLock);
  private final Lazy<DiagnosticIgnoranceComputer.Data> diagnosticIgnoranceData
    = new Lazy<>(this::computeDiagnosticIgnorance, computeLock);
  private final Lazy<MetricStorage> metrics = new Lazy<>(this::computeMetrics, computeLock);
  private final Lazy<List<Diagnostic>> diagnostics = new Lazy<>(this::computeDiagnostics, diagnosticsLock);

  public DocumentContext(URI uri, String content, ServerContext context, DiagnosticComputer diagnosticComputer) {
    this.uri = uri;
    this.content = content;
    this.context = context;
    this.diagnosticComputer = diagnosticComputer;

    this.tokenizer = new Tokenizer(content);
    this.fileType = computeFileType(this.uri);
  }

  public ServerContext getServerContext() {
    return context;
  }

  public String getContent() {
    requireNonNull(content);
    return content;
  }

  public String[] getContentList() {
    return contentList.getOrCompute();
  }

  public BSLParser.FileContext getAst() {
    requireNonNull(content);
    return tokenizer.getAst();
  }

  public SymbolTree getSymbolTree() {
    return symbolTree.getOrCompute();
  }

  public List<Token> getTokens() {
    requireNonNull(content);
    return tokenizer.getTokens();
  }

  public List<Token> getTokensFromDefaultChannel() {
    return getTokens().stream().filter(token -> token.getChannel() == DEFAULT_CHANNEL).collect(Collectors.toList());
  }

  public List<Token> getComments() {
    return getTokens().stream()
      .filter(token -> token.getType() == BSLLexer.LINE_COMMENT)
      .collect(Collectors.toList());
  }

  public String getText(Range range) {
    Position start = range.getStart();
    Position end = range.getEnd();

    String[] contentListUnboxed = getContentList();

    if (start.getLine() > contentListUnboxed.length || end.getLine() > contentListUnboxed.length) {
      throw new ArrayIndexOutOfBoundsException("Range goes beyond the boundaries of the parsed document");
    }

    String startString = contentListUnboxed[start.getLine()];
    StringBuilder sb = new StringBuilder();

    if (start.getLine() == end.getLine()) {
      sb.append(startString, start.getCharacter(), end.getCharacter());
    } else {
      sb.append(startString.substring(start.getCharacter())).append("\n");
    }

    for (int i = start.getLine() + 1; i <= end.getLine() - 1; i++) {
      sb.append(contentListUnboxed[i]).append("\n");
    }

    if (start.getLine() != end.getLine()) {
      sb.append(contentListUnboxed[end.getLine()], 0, end.getCharacter());
    }

    return sb.toString();
  }

  public MetricStorage getMetrics() {
    return metrics.getOrCompute();
  }

  public URI getUri() {
    return uri;
  }

  public FileType getFileType() {
    return fileType;
  }

  public ComplexityData getCognitiveComplexityData() {
    return cognitiveComplexityData.getOrCompute();
  }

  public ComplexityData getCyclomaticComplexityData() {
    return cyclomaticComplexityData.getOrCompute();
  }

  public DiagnosticIgnoranceComputer.Data getDiagnosticIgnorance() {
    return diagnosticIgnoranceData.getOrCompute();
  }

  public ModuleType getModuleType() {
    return moduleType.getOrCompute();
  }

  public Map<SupportConfiguration, SupportVariant> getSupportVariants() {
    return supportVariants.getOrCompute();
  }

  public Optional<MDObjectBase> getMdObject() {
    return Optional.ofNullable(getServerContext().getConfiguration().getModulesByObject().get(getUri()));
  }

  public List<Diagnostic> getDiagnostics() {
    return diagnostics.getOrCompute();
  }

  public List<Diagnostic> getComputedDiagnostics() {
    return Optional
      .ofNullable(diagnostics.get())
      .orElseGet(Collections::emptyList);
  }

  public void rebuild(String content) {
    computeLock.lock();
    clearSecondaryData();
    symbolTree.clear();
    this.content = content;
    tokenizer = new Tokenizer(content);
    computeLock.unlock();
  }

  public void clearSecondaryData() {
    computeLock.lock();
    diagnosticsLock.lock();
    content = null;
    contentList.clear();
    tokenizer = null;

    cognitiveComplexityData.clear();
    cyclomaticComplexityData.clear();
    metrics.clear();
    diagnosticIgnoranceData.clear();
    diagnostics.clear();
    diagnosticsLock.unlock();
    computeLock.unlock();
  }

  private static FileType computeFileType(URI uri) {
    String uriPath = uri.getPath();
    if (uriPath == null) {
      return FileType.BSL;
    }

    FileType fileTypeFromUri;
    try {
      fileTypeFromUri = FileType.valueOf(
        FilenameUtils.getExtension(uriPath).toUpperCase(Locale.ENGLISH)
      );
    } catch (IllegalArgumentException ignored) {
      fileTypeFromUri = FileType.BSL;
    }

    return fileTypeFromUri;
  }

  private String[] computeContentList() {
    return getContent().split("\n", -1);
  }

  private SymbolTree computeSymbolTree() {
    return new SymbolTreeComputer(this).compute();
  }


  private ModuleType computeModuleType() {
    return context.getConfiguration().getModuleType(uri);
  }

  private Map<SupportConfiguration, SupportVariant> computeSupportVariants() {
    return context.getConfiguration().getModuleSupport(uri);
  }

  private ComplexityData computeCognitiveComplexity() {
    Computer<ComplexityData> cognitiveComplexityComputer = new CognitiveComplexityComputer(this);
    return cognitiveComplexityComputer.compute();
  }

  private ComplexityData computeCyclomaticComplexity() {
    Computer<ComplexityData> cyclomaticComplexityComputer = new CyclomaticComplexityComputer(this);
    return cyclomaticComplexityComputer.compute();
  }

  private MetricStorage computeMetrics() {
    MetricStorage metricsTemp = new MetricStorage();
    final List<MethodSymbol> methodsUnboxed = getSymbolTree().getMethods();

    metricsTemp.setFunctions(Math.toIntExact(methodsUnboxed.stream().filter(MethodSymbol::isFunction).count()));
    metricsTemp.setProcedures(methodsUnboxed.size() - metricsTemp.getFunctions());

    int[] nclocData = getTokensFromDefaultChannel().stream()
      .mapToInt(Token::getLine)
      .distinct().toArray();
    metricsTemp.setNclocData(nclocData);
    metricsTemp.setNcloc(nclocData.length);

    metricsTemp.setCovlocData(computeCovlocData());

    int lines;
    final List<Token> tokensUnboxed = getTokens();
    if (tokensUnboxed.isEmpty()) {
      lines = 0;
    } else {
      lines = tokensUnboxed.get(tokensUnboxed.size() - 1).getLine();
    }
    metricsTemp.setLines(lines);

    int comments = (int) getComments()
      .stream()
      .map(Token::getLine)
      .distinct()
      .count();
    metricsTemp.setComments(comments);

    int statements = Trees.findAllRuleNodes(getAst(), BSLParser.RULE_statement).size();
    metricsTemp.setStatements(statements);

    metricsTemp.setCognitiveComplexity(getCognitiveComplexityData().getFileComplexity());
    metricsTemp.setCyclomaticComplexity(getCyclomaticComplexityData().getFileComplexity());

    return metricsTemp;
  }

  private int[] computeCovlocData() {

    return Trees.getDescendants(getAst()).stream()
      .filter(Predicate.not(TerminalNodeImpl.class::isInstance))
      .filter(DocumentContext::mustCovered)
      .mapToInt(node -> ((BSLParserRuleContext) node).getStart().getLine())
      .distinct().toArray();

  }

  private static boolean mustCovered(Tree node) {
    return node instanceof BSLParser.StatementContext
      || node instanceof BSLParser.GlobalMethodCallContext
      || node instanceof BSLParser.Var_nameContext;
  }

  private DiagnosticIgnoranceComputer.Data computeDiagnosticIgnorance() {
    Computer<DiagnosticIgnoranceComputer.Data> diagnosticIgnoranceComputer = new DiagnosticIgnoranceComputer(this);
    return diagnosticIgnoranceComputer.compute();
  }

  private List<Diagnostic> computeDiagnostics() {
    return diagnosticComputer.compute(this);
  }

}
