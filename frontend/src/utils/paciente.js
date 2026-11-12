// Mesmos formatos e dígitos verificadores de PacienteValidator; documentos continuam textos.
const EMAIL = /^[A-Za-z0-9!#$%&'*+/=?^_{|}~.-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/;

export function emailValido(valor) {
  const email = valor.trim().toLowerCase();
  const local = email.split('@')[0];
  return email.length <= 254 && local.length <= 64 && EMAIL.test(email)
    && !local.startsWith('.') && !local.endsWith('.') && !local.includes('..');
}

export function validarPaciente(values) {
  const errors = {};
  if (!values.nome.trim()) errors.nome = 'Informe o nome do paciente.';
  const cpf = values.cpf.trim();
  if (!cpf) errors.cpf = 'Informe o CPF do paciente.';
  else if (!/^(?:[0-9]{11}|[0-9]{3}\.[0-9]{3}\.[0-9]{3}-[0-9]{2})$/.test(cpf)) {
    errors.cpf = 'Informe 11 dígitos ou use 000.000.000-00.';
  } else {
    const digits = cpf.replace(/[.-]/g, '');
    const valid = [9, 10].every((length) => {
      const sum = [...digits.slice(0, length)].reduce((total, digit, index) => total + Number(digit) * (length + 1 - index), 0);
      return ((sum * 10) % 11) % 10 === Number(digits[length]);
    });
    if (/^(.)\1{10}$/.test(digits) || !valid) errors.cpf = 'CPF inválido: confira os dígitos verificadores.';
  }
  if (!values.email.trim()) errors.email = 'Informe o e-mail do paciente.';
  else if (!emailValido(values.email)) errors.email = 'E-mail inválido. Exemplo: nome@dominio.com.';
  return errors;
}

export function normalizarPaciente(values) {
  return { nome: values.nome.trim(), cpf: values.cpf.trim().replace(/[.-]/g, ''), email: values.email.trim().toLowerCase() };
}
