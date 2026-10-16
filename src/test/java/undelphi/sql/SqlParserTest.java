package undelphi.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import undelphi.sql.Ast.*;

class SqlParserTest {

    @Test
    void lexerSeparaParametroDeAtribuicao() {
        List<SqlLexer.Token> t = SqlLexer.tokenize("x := :COD_CLI || 'it''s' -- comentário\n /* bloco */ + 1.5");
        assertEquals(List.of("x", ":=", "COD_CLI", "||", "it's", "+", "1.5", ""),
                t.stream().map(SqlLexer.Token::text).toList());
        assertEquals(SqlLexer.Type.PARAM, t.get(2).type());
    }

    @Test
    void precedenciaEParentesesSobrevivemAoReimprimir() {
        Expr e = SqlParser.parseExpression("a + b * c - (d - e) / 2");
        assertEquals("a + b * c - (d - e) / 2".toUpperCase(), SqlPrinter.oracle(e));
        Binary top = assertInstanceOf(Binary.class, e);
        assertEquals("-", top.op());
    }

    @Test
    void joinExternoOracleFicaMarcadoNaColuna() {
        Expr e = SqlParser.parseExpression("A.X = B.Y(+) AND B.Z(+) = 1");
        Binary and = assertInstanceOf(Binary.class, e);
        Binary left = assertInstanceOf(Binary.class, and.left());
        assertTrue(assertInstanceOf(Col.class, left.right()).outer());
    }

    @Test
    void notInLikeBetweenIsNull() {
        String sql = "X NOT IN (1, 2) AND Y LIKE 'A%' AND Z NOT BETWEEN 1 AND 3 AND W IS NOT NULL";
        assertEquals(sql, SqlPrinter.oracle(SqlParser.parseExpression(sql)));
    }

    @Test
    void caseDecodeNvl() {
        Expr e = SqlParser.parseExpression(
                "CASE WHEN NVL(:P, 'N') = 'S' THEN V / DECODE(NVL(F, 0), 0, 1, F) ELSE V END");
        Case c = assertInstanceOf(Case.class, e);
        assertEquals(1, c.whens().size());
        assertInstanceOf(Func.class, ((Binary) c.whens().get(0).condition()).left());
    }

    @Test
    void selectComSubconsultaAliasEOuterJoin() {
        List<Stmt> stmts = SqlParser.parse("""
                SELECT A.COD, (SELECT MAX(X) FROM T2 WHERE T2.K = A.K) MX, B.NOME
                  FROM T1 A, (SELECT K, NOME FROM T3) B
                 WHERE A.K = B.K(+)
                 ORDER BY 1 DESC""");
        Query q = assertInstanceOf(Query.class, stmts.get(0));
        assertEquals(3, q.first().items().size());
        assertEquals("MX", q.first().items().get(1).alias());
        assertInstanceOf(SubqueryRef.class, q.first().from().get(1));
        assertTrue(q.orderBy().get(0).desc());
    }

    @Test
    void joinAnsi() {
        Query q = (Query) SqlParser.parse("SELECT * FROM A LEFT OUTER JOIN B ON B.ID = A.ID JOIN C ON C.X = B.X").get(0);
        JoinRef j = assertInstanceOf(JoinRef.class, q.first().from().get(0));
        assertEquals("JOIN", j.type());
        assertEquals("LEFT JOIN", assertInstanceOf(JoinRef.class, j.left()).type());
    }

    @Test
    void dmlComuns() {
        List<Stmt> s = SqlParser.parse("""
                INSERT INTO T (A, B) VALUES (:A, SEQ.NEXTVAL);
                UPDATE T X SET A = A + 1, B = :B WHERE X.ID = :ID;
                DELETE FROM T WHERE ID = 1""");
        assertEquals(3, s.size());
        assertInstanceOf(Insert.class, s.get(0));
        Update u = assertInstanceOf(Update.class, s.get(1));
        assertEquals("X", u.alias());
        assertEquals(2, u.sets().size());
        assertInstanceOf(Delete.class, s.get(2));
    }

    @Test
    void blocoPlsqlComCursorIfEExcecao() {
        List<Stmt> s = SqlParser.parse("""
                DECLARE
                  V NUMBER;
                BEGIN
                  FOR R IN (SELECT ID FROM T) LOOP
                    IF R.ID IS NULL THEN
                      V := 0;
                    ELSIF R.ID > 10 THEN
                      PKG.FAZ(R.ID, V);
                    ELSE
                      NULL;
                    END IF;
                  END LOOP;
                EXCEPTION
                  WHEN NO_DATA_FOUND THEN
                    V := 1;
                END;""");
        Block b = assertInstanceOf(Block.class, s.get(0));
        assertEquals(List.of("V NUMBER"), b.declarations());
        ForLoop f = assertInstanceOf(ForLoop.class, b.body().get(0));
        If i = assertInstanceOf(If.class, f.body().get(0));
        assertEquals(2, i.branches().size());
        assertInstanceOf(Call.class, i.branches().get(1).body().get(0));
        assertEquals(2, b.exceptionBody().size());
    }

    @Test
    void textoIncompletoViraRawSemDerrubarOResto() {
        List<Stmt> s = SqlParser.parse(" AND CODFILIAL = :FILIAL ORDER BY ; SELECT 1 FROM DUAL");
        assertInstanceOf(RawStmt.class, s.get(0));
        assertInstanceOf(Query.class, s.get(1));
    }

    @Test
    void createFunctionGuardaOTextoInteiro() {
        CreateObject c = assertInstanceOf(CreateObject.class,
                SqlParser.parse("CREATE OR REPLACE FUNCTION F(X IN DATE) RETURN NUMBER IS BEGIN RETURN 1; END;").get(0));
        assertEquals("FUNCTION", c.kind());
        assertEquals("F", c.name());
    }

    @Test
    void sqlRealDoExe9860ParseiaPorCompleto() throws Exception {
        String sql = TestFiles.read("sql/exeGerarPCCOTA.sql");
        List<Stmt> s = SqlParser.parse(sql);
        assertEquals(1, s.size(), () -> s.toString());
        Block b = assertInstanceOf(Block.class, s.get(0));
        ForLoop f = assertInstanceOf(ForLoop.class, b.body().get(0));
        assertEquals("REGISTROS", f.record());
        assertEquals(22, f.cursor().first().items().size());
        If i = assertInstanceOf(If.class, f.body().get(0));
        assertInstanceOf(Insert.class, i.branches().get(0).body().get(0));
        assertInstanceOf(Update.class, i.elseBody().get(0));
    }
}
