import { data, dataHora } from '../../formato';
import type { Ficha } from './tipos';

/** A ficha para ler: cada divisão com os exercícios. Usada pela equipe e pelo aluno. */
export function VerFicha({ ficha, semCabecalho = false }: { ficha: Ficha; semCabecalho?: boolean }) {
  return (
    <div className="ficha">
      {!semCabecalho && <p className="suave">
        Montada em {dataHora(ficha.montadaEm)}
        {ficha.corrigidaEm && ` · corrigida em ${dataHora(ficha.corrigidaEm)}`}
        {ficha.revisaoEm && ` · revisão em ${data(ficha.revisaoEm)}`}
        {ficha.encerradaEm && ` · encerrada em ${dataHora(ficha.encerradaEm)}`}
      </p>}
      {ficha.revisaoVencida && <p className="aviso">A data de revisão chegou: a ficha continua valendo até a nova.</p>}
      {ficha.divisoes.map((divisao) => (
        <section key={divisao.nome} className="divisao">
          <h3>Treino {divisao.nome}</h3>
          <ol>
            {divisao.exercicios.map((item, i) => (
              <li key={`${item.exercicioId}-${i}`}>
                <strong>{item.nome}</strong>
                <span> · {item.series} × {item.repeticoes}</span>
                {item.carga && <span> · {item.carga}</span>}
                {item.descansoSegundos !== null && <span> · descanso {item.descansoSegundos} s</span>}
                {item.observacao && <p className="suave">{item.observacao}</p>}
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  );
}
