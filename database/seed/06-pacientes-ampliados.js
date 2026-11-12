// Carga ampliada de pacientes fictícios (147 novos, além dos 3 de 03-pacientes.js).
//
// Gerados por uma sequência pseudoaleatória com semente fixa: toda carga produz
// exatamente os mesmos nomes, CPFs e e-mails, com os mesmos IDs.
// CPFs têm dígitos verificadores válidos; e-mails usam example.com (domínio reservado
// para exemplos, que não pertence a ninguém).
const TOTAL_NOVOS = 147;

function gerador(semente) { // mulberry32
  let a = semente >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const aleatorio = gerador(20261112);
const escolher = (lista) => lista[Math.floor(aleatorio() * lista.length)];

function cpf(base) {
  const d = base.split("").map(Number);
  for (const tamanho of [9, 10]) {
    const soma = d.slice(0, tamanho).reduce((s, n, i) => s + n * (tamanho + 1 - i), 0);
    const resto = (soma * 10) % 11;
    d.push(resto === 10 ? 0 : resto);
  }
  return d.join("");
}

const FEMININOS = ["Ana Clara", "Beatriz", "Camila", "Daniela", "Eduarda", "Fernanda", "Gabriela", "Helena",
  "Isabela", "Júlia", "Larissa", "Letícia", "Luana", "Mariana", "Natália", "Paula", "Rafaela", "Renata",
  "Sofia", "Tatiane", "Valentina", "Vitória", "Amanda", "Bianca", "Carolina", "Débora", "Elisa", "Giovana",
  "Lívia", "Manuela", "Priscila", "Sabrina", "Simone", "Yasmin", "Aline", "Cecília", "Lorena", "Marina"];
const MASCULINOS = ["André", "Arthur", "Bernardo", "Caio", "Daniel", "Davi", "Enzo", "Felipe", "Gabriel",
  "Guilherme", "Heitor", "Henrique", "Igor", "João Pedro", "Leonardo", "Lucas", "Matheus", "Miguel",
  "Murilo", "Nicolas", "Otávio", "Pedro", "Rafael", "Renato", "Rodrigo", "Samuel", "Thiago", "Vinícius",
  "Vitor", "Alexandre", "Carlos", "Fábio", "Leandro", "Marcos", "Ricardo", "Sérgio", "Paulo", "Roberto"];
const SOBRENOMES = ["Silva", "Santos", "Oliveira", "Souza", "Rodrigues", "Ferreira", "Alves", "Pereira",
  "Lima", "Gomes", "Costa", "Ribeiro", "Martins", "Carvalho", "Almeida", "Lopes", "Soares", "Fernandes",
  "Vieira", "Barbosa", "Rocha", "Dias", "Nascimento", "Andrade", "Moreira", "Nunes", "Marques", "Machado",
  "Mendes", "Freitas", "Cardoso", "Ramos", "Gonçalves", "Santana", "Teixeira", "Moura", "Correia",
  "Araújo", "Pinto", "Cavalcanti", "Batista", "Campos", "Monteiro", "Barros", "Rezende", "Toledo"];

const semAcento = (texto) => texto.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

// CPFs e e-mails já usados (03-pacientes.js), para não repetir.
const cpfsUsados = new Set(["39053344705", "12345678909", "98765432100"]);
const emailsUsados = new Set(["paciente@consultafacil.demo", "carla.souza@consultafacil.demo", "diego.santos@consultafacil.demo"]);
const nomesUsados = new Set();

const pacientes = [];
for (let i = 0; i < TOTAL_NOVOS; i++) {
  let nome;
  do {
    const primeiro = escolher(aleatorio() < 0.52 ? FEMININOS : MASCULINOS);
    const meio = escolher(SOBRENOMES);
    let ultimo = escolher(SOBRENOMES);
    while (ultimo === meio) ultimo = escolher(SOBRENOMES);
    nome = aleatorio() < 0.55 ? `${primeiro} ${meio} ${ultimo}` : `${primeiro} ${ultimo}`;
  } while (nomesUsados.has(nome));
  nomesUsados.add(nome);

  let numero;
  do {
    let base = "";
    for (let k = 0; k < 9; k++) base += Math.floor(aleatorio() * 10);
    numero = cpf(base);
  } while (cpfsUsados.has(numero) || /^(\d)\1{10}$/.test(numero));
  cpfsUsados.add(numero);

  const partes = semAcento(nome).replace(/\s+/g, " ").split(" ");
  let email = `${partes[0]}.${partes[partes.length - 1]}@example.com`;
  for (let sufixo = 2; emailsUsados.has(email); sufixo++) email = `${partes[0]}.${partes[partes.length - 1]}${sufixo}@example.com`;
  emailsUsados.add(email);

  const id = ObjectId("4" + (i + 4).toString(16).padStart(23, "0")); // 400…004 em diante
  pacientes.push({ _id: id, nome, cpf: numero, email });
}

pacientes.forEach(({ _id, ...campos }) =>
  db.pacientes.updateOne({ _id }, { $set: campos }, { upsert: true }));

print("OK carga 06: " + db.pacientes.countDocuments() + " pacientes");
