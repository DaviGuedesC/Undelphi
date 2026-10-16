package undelphi.target;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import undelphi.sql.Ast.*;
import undelphi.sql.SqlAnalysis;
import undelphi.target.OracleToPostgres.Issue;
import undelphi.target.OracleToPostgres.Severity;

/**
 * Traduz todos os comandos de uma SQL do exe para o destino. Blocos PL/SQL são traduzidos por partes
 * (cursor, condições, cada INSERT/UPDATE) para o gerador Go executar o fluxo; registros de cursor viram
 * parâmetros "REC.COLUNA".
 */
public final class SourceTranslator {

    /** SQL do exe traduzida: comandos no dialeto do destino e as lacunas/avisos. */
    public record Translated(SqlAnalysis analysis, List<Stmt> statements, List<Issue> issues) {

        public boolean translatable() {
            return issues.stream().noneMatch(i -> i.severity() == Severity.LACUNA);
        }

        public List<Issue> gaps() {
            return issues.stream().filter(i -> i.severity() == Severity.LACUNA).toList();
        }

        public List<Issue> warnings() {
            return issues.stream().filter(i -> i.severity() == Severity.AVISO).toList();
        }
    }

    private final TargetProfile profile;
    private final Set<Issue> issues = new LinkedHashSet<>();

    private SourceTranslator(TargetProfile profile) {
        this.profile = profile;
    }

    public static Translated translate(SqlAnalysis a, TargetProfile profile) {
        SourceTranslator t = new SourceTranslator(profile);
        List<Stmt> out = new ArrayList<>();
        if (a.kind() == SqlAnalysis.Kind.CHAMADA && a.statements().isEmpty()) {
            for (String call : a.calls()) {
                out.add(t.stmt(new Call(call, List.of()), Set.of()));
            }
        }
        for (Stmt s : a.statements()) {
            out.add(t.stmt(s, Set.of()));
        }
        if (a.kind() == SqlAnalysis.Kind.VAZIA) {
            t.issues.add(new Issue(Severity.LACUNA, "SQL vazia (montada em tempo de execução no código?)"));
        }
        return new Translated(a, out, List.copyOf(t.issues));
    }

    private Stmt stmt(Stmt s, Set<String> records) {
        return switch (s) {
            case Query q -> leaf(q, records);
            case Insert i -> leaf(i, records);
            case Update u -> leaf(u, records);
            case Delete d -> leaf(d, records);
            case Call c -> leaf(c, records);
            case Block b -> {
                if (!b.declarations().isEmpty()) {
                    issues.add(new Issue(Severity.AVISO, "bloco PL/SQL com variáveis locais: " + String.join("; ", b.declarations())));
                }
                if (!b.exceptionBody().isEmpty()) {
                    issues.add(new Issue(Severity.AVISO, "bloco PL/SQL com tratamento de exceção: revisar o equivalente em Go"));
                }
                yield new Block(b.declarations(), body(b.body(), records), body(b.exceptionBody(), records));
            }
            case ForLoop f -> {
                Query cursor = null;
                if (f.cursor() != null) {
                    cursor = (Query) leaf(f.cursor(), records);
                } else {
                    issues.add(new Issue(Severity.LACUNA, "FOR " + f.record() + " sem cursor SELECT explícito"));
                }
                Set<String> inner = new HashSet<>(records);
                inner.add(f.record());
                yield new ForLoop(f.record(), cursor, body(f.body(), inner));
            }
            case If i -> {
                List<Branch> branches = new ArrayList<>();
                for (Branch br : i.branches()) {
                    branches.add(new Branch(expr(br.condition(), records), body(br.body(), records)));
                }
                yield new If(branches, body(i.elseBody(), records));
            }
            case Assign a -> new Assign(a.target(), expr(a.value(), records));
            case Return r -> new Return(r.value() == null ? null : expr(r.value(), records));
            case NullStmt n -> n;
            case CreateObject c -> {
                issues.add(new Issue(Severity.LACUNA, "o exe cria " + c.kind() + " " + c.name()
                        + " no Oracle: reimplementar no destino ou mapear em funcoes:"));
                yield c;
            }
            case RawStmt r -> {
                if ("tratador de exceção".equals(r.reason())) {
                    yield r;
                }
                issues.add(new Issue(Severity.LACUNA, "SQL não entendida (" + r.reason() + "): "
                        + abbreviate(r.text())));
                yield r;
            }
        };
    }

    private List<Stmt> body(List<Stmt> stmts, Set<String> records) {
        List<Stmt> out = new ArrayList<>();
        for (Stmt s : stmts) {
            out.add(stmt(s, records));
        }
        return out;
    }

    private Stmt leaf(Stmt s, Set<String> records) {
        OracleToPostgres.Result r = OracleToPostgres.translate(s, profile, records);
        issues.addAll(r.issues());
        return r.stmt();
    }

    private Expr expr(Expr e, Set<String> records) {
        OracleToPostgres.Result r = OracleToPostgres.translateExpr(e, profile, records);
        issues.addAll(r.issues());
        return ((Return) r.stmt()).value();
    }

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 120 ? one.substring(0, 117) + "..." : one;
    }
}
