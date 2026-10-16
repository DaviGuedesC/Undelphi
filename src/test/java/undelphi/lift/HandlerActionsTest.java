package undelphi.lift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import undelphi.lift.HandlerActions.*;

/** Trechos reais do C decompilado do PCNOV9860 (TfrmPrincipal), já com os nomes do RTTI. */
class HandlerActionsTest {

    @Test
    void parametroComValorDoCampoDaTelaEFiltroDinamico() {
        String c = """
                void TfrmPrincipal::btnConsultaPesquisarClick(TfrmPrincipal *this,TObject *Sender)
                {
                  TScreen::set_Cursor(*_DAT_029975b0,0xffef);
                  if (cVar2 != '\\0') {
                    FUN_019ad230((*ref_dmDados9801)->qryCabecalho);
                    TCustomDADataSet::AddWhere
                              ((*ref_dmDados9801)->qryCabecalho,
                               L"NFE_CABECALHO.DTCADASTRO BETWEEN :DTINICIAL AND :DTFINAL");
                    TcxCustomDateEdit::get_Date(this->dtaPeriodoInicial);
                    TOraDataSet::ParamByName((*ref_dmDados9801)->qryCabecalho,L"DTINICIAL");
                    TParam::set_AsDateTime();
                    TcxCustomDateEdit::get_Date(this->dtaPeriodoFinal);
                    TOraDataSet::ParamByName((*ref_dmDados9801)->qryCabecalho,L"DTFINAL");
                    TParam::set_AsDateTime();
                    TWinControl::SetFocus(this->pnlFiltro);
                  }
                  ControlarActions(this,uStack_18,puVar1);
                  TfrmPrincipal::ControlarActions(this);
                  return;
                }""";
        List<Action> a = HandlerActions.extract(c, "frmPrincipal", "TfrmPrincipal");
        Ref qry = new Ref("dmDados9801", "qryCabecalho");
        assertTrue(a.contains(new DynamicWhere(qry, "AddWhere", "NFE_CABECALHO.DTCADASTRO BETWEEN :DTINICIAL AND :DTFINAL")), a::toString);
        assertTrue(a.contains(new SetParam(qry, "DTINICIAL", "AsDateTime", new Ref("frmPrincipal", "dtaPeriodoInicial"))), a::toString);
        assertTrue(a.contains(new SetParam(qry, "DTFINAL", "AsDateTime", new Ref("frmPrincipal", "dtaPeriodoFinal"))), a::toString);
        assertTrue(a.contains(new ReadField(new Ref("frmPrincipal", "dtaPeriodoInicial"), "Date")));
        assertTrue(a.contains(new CallOwn("ControlarActions")));
    }

    @Test
    void sqlMontadaEmCodigoComClearEAdd() {
        String c = """
                  TDataSet::Close((*ref_dmLogin)->qryPCExec);
                  uStack_38 = (double)CONCAT44(0x28d87ed,(undefined4)uStack_38);
                  piVar7 = (int *)TCustomDADataSet::get_SQL((*ref_dmLogin)->qryPCExec);
                  uStack_38 = (double)CONCAT44(0x28d87f2,(undefined4)uStack_38);
                  (**(code **)(*piVar7 + 0x48))();
                  piVar7 = (int *)TCustomDADataSet::get_SQL((*ref_dmLogin)->qryPCExec);
                  (**(code **)(*piVar7 + 0x3c))(piVar7,L"SELECT PCPRODUT.CODPROD");
                  piVar7 = (int *)TCustomDADataSet::get_SQL((*ref_dmLogin)->qryPCExec);
                  (**(code **)(*piVar7 + 0x3c))
                            (piVar7,L"     , DECODE(NVL(NFE_ITENS.FATOR, 0), 0, 1, NFE_ITENS.FATOR) FATOR ");
                  piVar7 = (int *)TCustomDADataSet::get_SQL((*ref_dmLogin)->qryPCExec);
                  (**(code **)(*piVar7 + 0x3c))(piVar7,L"  FROM NFE_ITENS");
                """;
        List<Action> a = HandlerActions.extract(c, "frmPrincipal", "TfrmPrincipal");
        Ref qry = new Ref("dmLogin", "qryPCExec");
        List<SqlText> sql = a.stream().filter(x -> x instanceof SqlText).map(x -> (SqlText) x).toList();
        assertEquals(4, sql.size(), a::toString);
        assertTrue(sql.get(0).clear());
        assertEquals("SELECT PCPRODUT.CODPROD", sql.get(1).line());
        assertEquals(qry, sql.get(3).dataset());
        assertTrue(a.contains(new Execute(qry, "Close")));
    }

    @Test
    void mensagensEscritaDeCampoENavegacao() {
        String c = """
                  TPCLinguas::Atencao(*(undefined4 *)(*_DAT_029939cc + 0x80),L"Informe a chave NFe base!");
                  TcxCustomEdit::set_Text(this->edtChaveNfe,L"");
                  TJvWizard::set_ActivePage(this->wzdPassoAPasso,this->pagProdutos);
                """;
        List<Action> a = HandlerActions.extract(c, "frmPrincipal", "TfrmPrincipal");
        assertTrue(a.contains(new Message("aviso", "Informe a chave NFe base!")), a::toString);
        assertTrue(a.contains(new WriteField(new Ref("frmPrincipal", "edtChaveNfe"), "Text")), a::toString);
        assertTrue(a.stream().anyMatch(x -> x instanceof Navigate), a::toString);
    }
}
