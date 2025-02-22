package org.klesun.deep_assoc_completion.entry.transpile;

import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.lang.Language;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl;
import com.jetbrains.php.lang.psi.PhpElementType;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.elements.impl.*;
import org.apache.commons.lang.StringEscapeUtils;
import org.klesun.deep_assoc_completion.resolvers.ClosRes;
import org.klesun.deep_assoc_completion.resolvers.FuncCallRes;
import org.klesun.deep_assoc_completion.resolvers.VarRes;
import org.klesun.lang.It;
import org.klesun.lang.L;
import org.klesun.lang.Opt;
import org.klesun.lang.Tls;

import static org.klesun.lang.Lang.*;
import static org.klesun.lang.Tls.getChildrenWithLeaf;

public class TranspileToNodeJs extends AnAction
{
    private It<PsiElement> removeClsMods(It<PsiElement> clsParts)
    {
        return clsParts
            .flt(part -> opt(part.getNode().getElementType())
                .cst(PhpElementType.class)
                .all(elt -> {
                    String debugName = elt.toString();
                    return !list("abstract", "final").contains(debugName);
                }))
                ;
    }

    private String getRootPath(PsiElement pathPsi)
    {
        return Tls.findParent(pathPsi, PhpNamespace.class, a -> true)
            .map(ns -> L(ns.getChildren()).cst(PhpNamespaceReference.class)
                .map(nsref -> L(nsref.getText().split("\\\\"))
                    .map(dirname -> "..").str("/")).fst().def(""))
            .map(ns -> ns + "/..") // current dir is a separate PSI
            .def("");
    }

    private String makeImport(PhpReference pathPsi, Boolean fromRoot)
    {
        String path = pathPsi.getText().replace("\\", "/");
        String root = getRootPath(pathPsi);
        if (fromRoot && !path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.startsWith("/")) {
            return "require('" + root + path + ".js')";
        } else {
            return "require('" + path + ".js')";
        }
    }

    private Opt<String> transpileImport(PhpUseListImpl lst)
    {
        return It(lst.getDeclarations())
            .fal(usePsi -> opt(usePsi.getTargetReference())
            .map(pathPsi -> {
                String require = makeImport(pathPsi, true);
                String alias = opt(usePsi.getAliasName()).def(pathPsi.getName());
                return "const " + alias + " = " + require + ";";
            }))
            .map(vals -> vals.itr().str("\n"));
    }

    private String getIndent(PsiElement psi)
    {
        return opt(psi.getPrevSibling())
            .cst(PsiWhiteSpaceImpl.class)
            .fop(ws -> Tls.regex("^(.*\\n|)(\\s*?)$", ws.getText()))
            .fop(ma -> ma.gat(1))
            .def("");
    }

    private String getStIndent(PsiElement psi)
    {
        return It.cnc(som(psi), Tls.getParents(psi))
            .fap(par -> opt(par.getPrevSibling())
                .cst(PsiWhiteSpaceImpl.class)
                .fop(ws -> Tls.regex("^(\\s*\\n)(\\s*?)$", ws.getText()))
                .fop(ma -> ma.gat(1)))
            .fst().def("");
    }

    private String transpileFunction(Function typed)
    {
        It<PsiElement> stats = Tls.findChildren(typed, GroupStatement.class)
            .fst().fap(gr -> getChildrenWithLeaf(gr))
            .flt(leaf -> !leaf.getText().equals("{"));
        L<PsiElement> args = L(typed.getParameters());
        String name = typed.getName();
        name = "__construct".equals(name) ? "constructor" : name;

        L<String> argNames = args.itr().cst(Parameter.class)
            .map(arg -> arg.getName()).arr();
        L<String> closureVars = ClosRes.getClosureVars(typed)
            .map(v -> v.getName()).arr();
        L<String> usedVars = opt(typed.getLastChild())
            .fap(grpst -> FuncCallRes.findUsedVars(grpst))
            .map(v -> v.getName())
            .flt(varName -> !argNames.contains(varName))
            .flt(varName -> !closureVars.contains(varName))
            .flt(varName -> !varName.equals("this"))
            .unq().arr();

        String mods = Tls.cast(Method.class, typed)
            .map(meth -> meth.isStatic() ? "static " : "").def("");
        Boolean isMeth = Tls.cast(Method.class, typed).has();
        return mods + name + "("
            + args.map(arg -> trans(arg)).str(", ")
            + ") " + (isMeth ? "" : "=>") + " {\n"
            + (usedVars.size() == 0 ? "" :
                getStIndent(typed) + "    let " + Tls.implode(", ", usedVars.map(v -> "$" + v)) + ";\n")
            + stats.map(st -> trans(st)).str("");
    }

    private Opt<String> transpileArray(ArrayCreationExpression typed)
    {
        L<ArrayHashElement> hashes = L(typed.getHashElements());
        if (hashes.size() > 0) {
            L<String> subTokens = getChildrenWithLeaf(typed)
                .arr().sub(1, -1).map(c -> trans(c)).arr();
            return som("{" + subTokens.str("") +  "}");
        } else {
            L<PhpExpression> els = It(typed.getChildren())
                .fap(valuePsi -> opt(valuePsi.getFirstChild()))
                .cst(PhpExpression.class).arr();
            return els.gat(1)
                .cst(StringLiteralExpression.class)
                .map(lit -> lit.getContents())
                .flt(methName -> els.size() == 2)
                .fop(methName -> {
                    PhpExpression obj = els.get(0);
                    if (obj.getText().equals("$this") || obj.getText().endsWith("::class")) {
                        obj = Tls.cast(ClassConstantReference.class, obj)
                            .map(ref -> ref.getClassReference()).def(obj);
                        return som("(...args) => " + trans(obj) + "." + methName + "(...args)");
                    } else {
                        return non();
                    }
                });
        }
    }

    private String transpileCatch(Catch typed)
    {
        Opt<Catch> prevCatch = Tls.findPrevSibling(typed, Catch.class);
        if (prevCatch.has()) {
            return ""; // you can't have 2 catch-es in javascript
        }
        It<PsiElement> stats = Tls.findChildren(typed, GroupStatement.class)
            .fst().fap(gr -> It(gr.getStatements()));
        return "catch (" + typed.getException().getText() + ") {\n"
            + stats.map(st -> getIndent(st) + trans(st)).str("\n")
            + "}";
    }

    private Opt<String> transpileForeach(ForeachImpl typed)
    {
        It<PsiElement> stats = Tls.findChildren(typed, GroupStatement.class)
            .fst().fap(gr -> It(gr.getStatements()));
        return VarRes.parseForeach(typed)
            .fop(tup -> tup.nme((arr, keyOpt, valOpt, tuple) -> {
                Opt<String> valuePartOpt = Opt.fst(
                    () -> valOpt.map(vari -> vari.getText()),
                    () -> som("[" + tuple.map(vari -> vari.getText()).str(", ") + "]")
                );
                return valuePartOpt.map(valuePart -> {
                    String arrPart = trans(arr);
                    String content = keyOpt
                        .map(key -> "[" + key.getText() + ", " + valuePart + "] of Object.entries(" + arrPart + ")")
                        .def(valuePart + " of Object.values(" + arrPart + ")");
                    return "for (" + content + ") {\n"
                        + stats.map(st -> getIndent(st) + trans(st)).str("\n")
                        + "}";
                });
            }));
    }

    /**
     * @param nullableExpr = $arr['optKey1']['optKey2']['optKey3']
     * @return = (($arr['optKey1'] || {})['optKey2'] || {})['optKey3']
     */
    private String safeKeys(PsiElement nullableExpr)
    {
        return opt(nullableExpr)
            .cst(ArrayAccessExpression.class)
            // variable supposedly should have been null-checked before being accessed
            .flt(acc -> !opt(acc.getValue()).any(v -> v instanceof Variable))
            .fop(acc -> opt(acc.getIndex()).map(idx -> {
                return "(" + safeKeys(acc.getValue()) + " || {})[" + idx.getText() + "]";
            }))
            .def(trans(nullableExpr));
    }

    private String trans(PsiElement psi)
    {
        if (psi == null) {
            return "";
        }

        // TODO: class constants and static properties
        // TODO: put instance properties in constructor - node does not allow properties directly in class body
        // TODO: `if (!$atfqInfo = this.parseAtfqLine($line)) {` -> `if (!($atfqInfo = this.parseAtfqLine($line))) {`
        // TODO: `[1,2,3] === [1,2,3]` -> `php.equals([1,2,3], [1,2,3], true)`
        // TODO: `if ($value)` -> `if (!php.empty($value))` because arrays:
        //         if ()
        //         ?:
        //         $value && $value2
        // TODO: `[static::class, 'parseFopModifier']` -> `a => this.parseFopModifier(a)` or `a => this.prototype.parseFopModifier(a)`
        Iterable<String> result = It.frs(() -> It.non()
            , () -> Tls.cast(LeafPsiElement.class, psi)
                .map(leaf ->
                    leaf.getText().equals("<?php") ? "" :
                    leaf.getText().equals("(int)") ? "+" :
                    leaf.getText().equals(".") ? "+" :
                    leaf.getText().equals(".=") ? "+=" :
                    leaf.getText().equals("??") ? "||" :
                    leaf.getText().equals("namespace") ? "// namespace" :
                    leaf.getText().equals("elseif") ? "else if" :
                    leaf.getText().equals("empty") ? "php.empty" :
                    leaf.getText().equals("isset") ? "php.isset" :
                    leaf.getText().equals("unset") ? "delete" :
                    leaf.getText())
            , () -> Tls.cast(ClassReferenceImpl.class, psi)
                .map(ref -> {
                    if (ref.getText().equals("self") || ref.getText().equals("static")) {
                        Boolean calledInStatic = Tls
                            .findParent(psi, Method.class, a -> true)
                            .any(m -> m.isStatic());
                        if (calledInStatic) {
                            return "this";
                        } else {
                            return "this.constructor";
                        }
                    } else if (ref.getText().equals("\\Exception")) {
                        return "Error";
                    } else {
                        String clsPath = ref.getText();
                        if (!clsPath.contains("\\")) {
                            return clsPath;
                        } else {
                            return makeImport(ref, false);
                        }
                    }
                })
            , () -> Tls.cast(PhpClass.class, psi)
                .fap(cls -> {
                    It<PsiElement> parts = getChildrenWithLeaf(cls);
                    return It.cnc(
                        som("const php = require('" + getRootPath(cls) + "/php.js');\n"),
                        removeClsMods(parts)
                            .map(part -> trans(part)),
                        It(cls.getFields())
                            .flt(fld -> fld.isConstant() || fld.getModifier().isStatic())
                            .map(fld -> "\n" + cls.getName() + "." + fld.getName() + " = " +
                                opt(fld.getDefaultValue()).map(v -> trans(v)).def(null) + ";"),
                        som("\nmodule.exports = " + cls.getName() + ";")
                    );
                })
            , () -> Tls.cast(PhpPsiElementImpl.class, psi)
                .flt(miscPsi -> miscPsi.getText().startsWith("const "))
                .map(a -> "") // should have been handled by PhpClass
            , () -> Tls.cast(PhpUseListImpl.class, psi)
                .fap(typed -> transpileImport(typed))
            , () -> Tls.cast(PhpModifierList.class, psi)
                .map(typed -> "")
            , () -> Tls.cast(Function.class, psi)
                .map(typed -> transpileFunction(typed))
            , () -> Tls.cast(Parameter.class, psi)
                .map(typed -> (typed.getText().startsWith("...") ? "..." : "") + "$" + typed.getName())
            , () -> Tls.cast(Variable.class, psi)
                .map(typed -> typed.getText().equals("$this") ? "this" : typed.getText())
            , () -> Tls.cast(FieldReference.class, psi)
                .map(typed -> trans(typed.getClassReference()) + ".$" + typed.getName())
            , () -> Tls.cast(MethodReference.class, psi)
                .map(typed -> trans(typed.getClassReference()) + "." + typed.getName() + "(" + trans(typed.getParameterList()) + ")")
            , () -> Tls.cast(FunctionReference.class, psi)
                .fap(typed -> {
                    PsiElement[] args = typed.getParameters();
                    if (typed.getText().equals("func_get_args()")) {
                        return som("arguments");
                    } else if ("preg_match".equals(typed.getName()) && args.length > 2) {
                        String preArgs = L(args).sub(0, 2).map(arg -> trans(arg)).str(", ");
                        String matchesVar = args[2].getText();
                        String postArgs = L(args).sub(3).map(arg -> ", " + trans(arg)).str("");
                        return som("php.preg_match(" + preArgs + ", " + matchesVar + " = []" + postArgs + ")");
                    } else if ("preg_match_all".equals(typed.getName()) && args.length > 2) {
                        String preArgs = L(args).sub(0, 2).map(arg -> trans(arg)).str(", ");
                        String matchesVar = args[2].getText();
                        String postArgs = L(args).sub(3).map(arg -> ", " + trans(arg)).str("");
                        return som("php.preg_match_all(" + preArgs + ", " + matchesVar + " = []" + postArgs + ")");
                    } else {
                        return opt(typed.getName()).flt(n -> !"".equals(n))
                            .map(n -> "php." + n + "(" + trans(typed.getParameterList()) + ")");
                    }
                })
            , () -> Tls.cast(ArrayCreationExpression.class, psi).itr()
                .fap(typed -> transpileArray(typed))
            , () -> Tls.cast(Catch.class, psi).itr()
                .map(typed -> transpileCatch(typed))
            , () -> Tls.cast(ForeachImpl.class, psi).itr()
                .fap(typed -> transpileForeach(typed))
            , () -> Tls.cast(ClassConstantReference.class, psi)
                .map(typed -> trans(typed.getClassReference()) + '.' + typed.getName())
            , () -> Tls.cast(ConstantReferenceImpl.class, psi)
                .map(cst -> list("true", "false", "null").contains(cst.getText())
                    ? cst.getText() : "php." + cst.getText())
            , () -> Tls.cast(StringLiteralExpression.class, psi)
                .map(typed -> {
                    if (!typed.isSingleQuote()) {
                        return typed.getText();
                    } else {
                        String content = typed.getContents();
                        return Tls.regex("\\/(.*[^\\\\])\\/([a-z]{0,3})", content)
                            .map(m -> "/" + m.get(0) + "/" + m.get(1)) // '/\s*/i' -> /\s*/i
                            .def("'" + StringEscapeUtils.escapeJavaScript(typed.getContents()) + "'");
                    }
                })
            , () -> Tls.cast(AssignmentExpression.class, psi)
                .fap(ass -> {
                    // $arr[] = 1 + 1; -> $arr.push(1 + 1);
                    return opt(ass.getVariable())
                        .cst(ArrayAccessExpression.class)
                        .flt(acc -> opt(acc.getIndex()).map(idx -> idx.getText()).def("").equals(""))
                        .fap(acc -> opt(ass.getValue()).map(el -> trans(el))
                            .fap(eltxt -> opt(acc.getValue()).map(el -> trans(el))
                                .map(arrtxt -> arrtxt + ".push(" + eltxt + ")")));
                })
            , () -> Tls.cast(ArrayHashElement.class, psi)
                .fap(hash -> opt(hash.getKey())
                    .fop(key -> opt(hash.getValue())
                        .map(val -> {
                            String keyExpr = trans(key);
                            if (!keyExpr.startsWith("\"") && !keyExpr.startsWith("'") ||
                                !(key instanceof StringLiteralExpression)
                            ) {
                                keyExpr = "[" + keyExpr + "]";
                            }
                            return keyExpr + ": " + trans(val);
                        })))
            , () -> Tls.cast(MultiassignmentExpression.class, psi)
                .map(multiass -> {
                    It<String> vars = It(multiass.getVariables()).map(v -> trans(v));
                    return "[" + vars.str(", ") + "] = " +
                        opt(multiass.getValue())
                            .map(v -> trans(v)).def("");
                })
            , () -> Tls.cast(TernaryExpression.class, psi)
                .flt(tern -> tern.isShort())
                .map(tern -> trans(tern.getCondition()) +
                    " || " + trans(tern.getFalseVariant()))
            , () -> Tls.cast(BinaryExpression.class, psi)
                .flt(bin -> som("??").equals(opt(bin.getOperation()).map(o -> o.getText())))
                .map(coal -> {
                    String fallback = trans(coal.getRightOperand());
                    return safeKeys(coal.getLeftOperand()) +
                        (fallback.equals("null") ? "" : " || " + fallback);
                })
        );
        return It(result).def(getChildrenWithLeaf(psi)
            .map(c -> trans(c))).str("");
    }

    public static void openAsScratchFile(String text, AnActionEvent e, String name)
    {
        opt(e.getData(LangDataKeys.PROJECT)).thn(project -> {
            Language lang = L(Language.findInstancesByMimeType("text/javascript")).fst()
                .def(PlainTextLanguage.INSTANCE);
            VirtualFile file = ScratchRootType.getInstance().createScratchFile(
                project, name, lang,
                text, ScratchFileService.Option.create_new_always
            );
            if (file != null) {
                PsiNavigationSupport.getInstance().createNavigatable(project, file, 0).navigate(true);
                PsiFile nullPsiFile = PsiManager.getInstance(project).findFile(file);
                opt(e.getData(LangDataKeys.IDE_VIEW))
                    .thn(ideView -> opt(nullPsiFile)
                        .thn(psiFile -> ideView.selectElement(psiFile)));
            }
        });
    }

    @Override
    public void actionPerformed(AnActionEvent e)
    {
        String output = opt(e.getData(LangDataKeys.PSI_FILE))
            .map(psiFile -> It(psiFile.getChildren()).map(psi -> trans(psi)))
            .map(psiTexts -> Tls.implode("", psiTexts))
            .def("Error: could not retrieve current file");
        System.out.println(output);
        openAsScratchFile(output, e, "transpiled.js");
    }
}
