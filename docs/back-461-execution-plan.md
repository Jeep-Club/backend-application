# BACK-461 — replanejamento de capacidade e histórico

Base de trabalho: `e594265d270544ee31c7123258d2a7e2d501da94` (branch `461`).
Este plano registra a ampliação confirmada após a reestruturação da Story; não
significa que os marcos estejam implementados ou aprovados para produção.

## Decisões de produto

- Registrar a capacidade ao inscrever ou alocar o veículo e sua evolução em
  cada alteração posterior. A capacidade corrente continua limitando novas
  alocações; snapshots não autorizam exceder a capacidade física.
- Recusar redução que deixe ocupação acima da capacidade proposta. A lotação
  deve ser resolvida no Event antes de repetir a edição.
- Para inscrições legadas sem snapshot, a capacidade inicial é desconhecida;
  não apresentar a capacidade atual como fato histórico.
- A orientação posterior de produto substitui a consulta histórica operacional:
  excluir dados de participantes, convidados e operação no hard delete, e
  preservar fatos financeiros, histórico editorial e auditoria já existentes.
- Alocações do membro e dependentes continuam sendo alteradas pelo próprio
  membro; administrador mantém as operações de convidados já existentes.

## Marcos replanejados e gates

1. Concluir recusa de carona e precisão da auditoria Health, incluindo falha
   do registro obrigatório e contratos HTTP.
2. Projetar e implementar alteração atômica de capacidade nos fluxos de membro
   e administrador, com guests, remoção/realocação e corridas de última vaga.
   Evitar inversão entre o lock Event → Vehicle e a edição de Vehicle. Manter
   os guards existentes de exclusão/inativação. Exercitar MySQL 8 real.
3. Persistir snapshot inicial e alterações com Flyway append-only; mapear legado
   como desconhecido. Associar revisão de risco ao hash da migration, cobrir
   upgrade histórico, rollback operacional e ensaio V+1 no HEAD final.
4. Alterar o hard delete segundo a nova decisão; inventariar dados operacionais,
   históricos, auditoria e fatos financeiros, preservando os boundaries e
   validando cascatas, rollback e compatibilidade dos consumidores.
5. Reconciliar discovery e XMLs nas faixas F1–F5, F3/F4 complementares, gates
   `verify` e `mysql-migrations`, e documentação proprietária no SHA final.

## Compatibilidade

A exclusão de dados operacionais substitui o aceite inicial de reconstrução
pós-exclusão. A V17 cria a tabela de histórico de capacidade sem preencher
valores iniciais legados e sem expurgar retroativamente eventos já excluídos.
A consulta de auditoria de convidados usa os identificadores retidos nas
transições, que seguem disponíveis após a remoção do cadastro operacional.
