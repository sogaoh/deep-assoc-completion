package org.klesun.deep_keys.entry;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.Method;
import org.klesun.deep_keys.DeepType;
import org.klesun.deep_keys.DeepTypeResolver;
import org.klesun.lang.Opt;

import java.util.List;

import static org.klesun.lang.Lang.*;

public class RunTest extends AnAction
{
    private static Opt<List<Method>> findTestDataPvdrFuncs(PsiFile psiFile)
    {
        List<Method> meths = list();

        L(PhpIndex.getInstance(psiFile.getProject()).getClassesByName("UnitTest")).s
            .forEach(cls -> meths.addAll(L(cls.getMethods())
                .flt(m -> m.getName().startsWith("provide")).s));

        return meths.size() > 0 ? opt(meths) : opt(null);
    }

    @Override
    public void actionPerformed(AnActionEvent e)
    {
        System.out.println("Searching for \"UnitTest\" class in project...");
        List<Error> errors = opt(e.getData(LangDataKeys.PSI_FILE))
            .fap(file -> findTestDataPvdrFuncs(file))
            .map(funcs -> L(funcs).fap(func ->
                L(DeepTypeResolver.findFuncRetType(func, 30).get(0).indexTypes)
                    .fop((rett, i) -> {
                        CaseContext ctx = new CaseContext();
                        ctx.dataProviderName = func.getName();
                        ctx.testNumber = i;
                        return opt(rett.keys.get("0"))
                            .fap(input -> opt(rett.keys.get("1"))
                                .map(output -> ctx.testCase(input, output)));
                    })
                    .s).fap(v -> v).s)
            .els(() -> System.out.println("Failed to find data-providing functions"))
            .def(list());

        System.out.println();
        errors.forEach(err -> {
            System.out.println(
                "Error in " + err.dataProviderName + " #" + err.testNumber + " " +
                L(err.keyChain).rdc((a,b) -> a + ", " + b, "") + " " + err.message
            );
        });

        System.out.println("Done testing with " + errors.size() + " errors");
    }

    private static class CaseContext
    {
        String dataProviderName;
        List<String> keyChain = list();
        int testNumber;

        private List<Error> testCase(DeepType.Key actual, DeepType.Key expected)
            throws AssertionError // in case input does not have some of output keys
        {
            List<Error> errors = list();

            DeepType actualt = actual.types.get(0);
            DeepType expectedt = expected.types.get(0);
            expectedt.keys.forEach((subKey, subExpected) -> opt(actualt.keys.get(subKey))
                .thn(subActual -> {
                    System.out.print(".");
                    keyChain.add(subKey);
                    testCase(subActual, subExpected);
                    keyChain.remove(keyChain.size() - 1);
                })
                .els(() -> errors.add(new Error(this, "No key - " + subKey, subExpected))));

            return errors;
        }
    }

    private static class Error
    {
        DeepType.Key keyRecord;
        String message;
        String dataProviderName;
        List<String> keyChain;
        int testNumber;

        Error(CaseContext ctx, String msg, DeepType.Key key)
        {
            this.dataProviderName = ctx.dataProviderName;
            this.keyChain = ctx.keyChain.subList(0, ctx.keyChain.size());
            this.testNumber = ctx.testNumber;
            this.message = msg;
            this.keyRecord = key;
        }
    }
}
