# Fluxos do Billing

## Configuração até a dívida individual

```text
ChargeDefinition ACTIVE
  -> ChargeAssignment ativa
  -> geração do ChargeCycle
  -> resolução e deduplicação de usuários ativos
  -> snapshots da configuração
  -> uma MemberCharge PENDING por usuário
```

`ALL_MEMBERS` consulta Identity; `USER` valida um usuário ativo; `ROLE` consulta
Authorization e filtra usuários ativos. O adapter de eventos consulta participantes
confirmados por `EventQuery` para a geração administrativa tradicional.

No fluxo específico de Event, a inscrição garante o assignment daquele Event,
um único `EventChargeContext`/ciclo para Event + definição e uma MemberCharge
por usuário/ciclo. Não resolve ALL_MEMBERS, USER, ROLE ou outro Event da definição.
O lock da definição serializa a primeira criação e os incrementos. Não é preciso
estar CONFIRMED para receber a dívida. O cancelamento do Event consulta apenas os
contextos daquele Event e trata ciclos `GENERATED`, `FINISHED` e `ARCHIVED`.
Preserva fechamento/arquivamento, cancela dívidas abertas e mantém pagamentos.
Desistência individual preserva dívidas e usa o fluxo existente de request de refund;
consulte a [decisão BACK-460](../../../../../../../../docs/back-460-event-billing.md).

## Encerramento do ciclo

- `finish`: altera somente o ciclo para `FINISHED`.
- `cancel`: cancela cobranças abertas, preserva pagamentos e cria, quando
  aplicável, refunds `ELIGIBLE` por 30 dias.
- `archive`: parte de `FINISHED` ou `CANCELED` e não altera cobranças, pagamentos
  ou refunds.

## Submissão e substituição de pagamento

Na submissão, o serviço bloqueia a cobrança, valida ownership, janela, valor e
unicidade do pagamento editável; depois valida e armazena o comprovante, registra
a compensação e persiste `MemberPayment(PENDING_VALIDATION)`.

Na substituição, bloqueia pagamento e cobrança nessa ordem, valida estado e
ownership, armazena o novo arquivo e persiste a nova key. Se houver commit, o
arquivo anterior é removido; em rollback, o novo é compensado.

O download recebe `paymentId`, carrega pagamento e cobrança, autoriza owner ou
`BILLING_PAYMENT_READ` e somente então usa a key interna em `FileStorage.load`.

## Validação administrativa

```text
PENDING_VALIDATION --confirm--> CONFIRMED + MemberCharge PAID
PENDING_VALIDATION --reject---> REJECTED + MemberCharge permanece PENDING
REJECTED --member resubmits---> PENDING_VALIDATION
```

Pagamento enviado dentro da janela pode ser confirmado depois porque a
confirmação valida estado, não recalcula a janela.

### Contexto administrativo de pagamentos e cobranças

A lista e o detalhe de pagamentos, inclusive após confirmar ou rejeitar,
identificam o titular pela cobrança vinculada. O vencimento vem da cobrança.
O nome histórico da definição e a referência do ciclo vêm do ciclo gravado.
Ajustar o valor final ou cancelar uma cobrança devolve o mesmo contexto
do detalhe administrativo por ID.

Nomes atuais, inclusive de quem confirmou ou rejeitou, exigem IDENTITY_USER_READ.
A matrícula vigente exige MEMBERSHIP_MEMBER_READ. Campos protegidos são
nulos quando indisponíveis. O autosserviço mantém o contrato de envio e
substituição de pagamentos.

## Reembolso

O membro pode criar `REQUESTED` a partir de pagamento próprio confirmado.
Se já houver refund ativo de cancelamento de ciclo, a
solicitação converte `ELIGIBLE` em `REQUESTED` em vez de criar outro registro.

O administrador aprova, rejeita, marca como devolvido, expira ou cancela conforme
a máquina de estados. O código não executa expiração automática.

Cancelamento não cria refund para PENDING_VALIDATION. Confirmar esse pagamento
depois cria ELIGIBLE sem duplicar refund ativo/concluído, preservando a janela
de 30 dias contada do instante mais recente entre cancelamento e confirmação.
Uma confirmação tardia sempre possui uma janela utilizável completa.

As respostas administrativas de reembolso identificam o beneficiário, a
matrícula vigente e a obrigação vinculada. O detalhe e as transições também
apresentam os nomes disponíveis dos atores. Nomes exigem IDENTITY_USER_READ e
matrícula exige MEMBERSHIP_MEMBER_READ. Vínculos financeiros inconsistentes
ficam sem rótulos históricos. O contrato do associado permanece separado.


### Apresentação administrativa de atribuições e exclusões

A listagem, o detalhe e as mutações de atribuições retornam o nome atual da
definição. Os rótulos do alvo seguem a permissão de leitura de usuário, cargo
ou evento e são resolvidos em lote na listagem. A consulta pública interna de
Publications também pode fornecer o título preservado de um evento excluído.

O histórico de exclusão da definição preserva o snapshot e o ID do autor. A
resposta HTTP inclui o nome cadastral atual do autor ativo quando o solicitante
possui `IDENTITY_USER_READ`. A consulta exige
`BILLING_CHARGE_DEFINITION_HISTORY_READ`, é auditada e usa `Cache-Control: no-store`.
