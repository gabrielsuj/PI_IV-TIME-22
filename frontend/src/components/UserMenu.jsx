import { useEffect, useId, useRef, useState } from 'react';
import { useAuth } from '../hooks/useAuth';
import { NOME_PERFIL } from '../data/menu';

/** Iniciais para o avatar: "Ana Martins — Fictícia" → "AM". */
function iniciais(nome = '') {
  const partes = nome.replace(/\s+—.*$/, '').trim().split(/\s+/).filter(Boolean);
  const letras = (partes[0]?.[0] ?? '') + (partes.length > 1 ? partes.at(-1)[0] : '');
  return letras.toUpperCase() || '?';
}

/** Avatar do usuário logado no cabeçalho, com nome, perfil e a opção de sair. */
export default function UserMenu() {
  const { usuario, sair } = useAuth();
  const [aberto, setAberto] = useState(false);
  const [saindo, setSaindo] = useState(false);
  const raiz = useRef(null);
  const painelId = useId();

  useEffect(() => {
    if (!aberto) return undefined;
    const fecharAoClicarFora = (event) => {
      if (!raiz.current?.contains(event.target)) setAberto(false);
    };
    const fecharComEsc = (event) => {
      if (event.key === 'Escape') setAberto(false);
    };
    document.addEventListener('mousedown', fecharAoClicarFora);
    document.addEventListener('keydown', fecharComEsc);
    return () => {
      document.removeEventListener('mousedown', fecharAoClicarFora);
      document.removeEventListener('keydown', fecharComEsc);
    };
  }, [aberto]);

  if (!usuario) return null;
  const perfil = NOME_PERFIL[usuario.perfil] ?? usuario.perfil;

  async function encerrar() {
    setSaindo(true);
    await sair();
  }

  return (
    <div className="user-menu" ref={raiz}>
      <button
        type="button"
        className="user-menu__trigger"
        aria-label={`Conta de ${usuario.nome}`}
        aria-expanded={aberto}
        aria-controls={painelId}
        onClick={() => setAberto((valor) => !valor)}
      >
        <span className="user-menu__avatar" aria-hidden="true">{iniciais(usuario.nome)}</span>
        <span className="user-menu__label" aria-hidden="true">
          <strong>{usuario.nome}</strong>
          <small>{perfil}</small>
        </span>
        <span className="user-menu__caret" aria-hidden="true">▾</span>
      </button>
      {aberto && (
        <div className="user-menu__panel" id={painelId}>
          <p className="user-menu__who">
            <strong>{usuario.nome}</strong>
            <span>{perfil}</span>
          </p>
          <button type="button" className="user-menu__item" onClick={encerrar} disabled={saindo}>
            {saindo ? 'Saindo…' : 'Sair'}
          </button>
        </div>
      )}
    </div>
  );
}
