/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

import { useState } from 'react';
import { Outlet, useOutletContext } from 'react-router';
import RegistrationPage from './RegistrationPage';

export default function PacientesLayout() {
  const busca = useState('');
  return <Outlet context={busca} />;
}

export function ListaPacientes() {
  const busca = useOutletContext();
  return <RegistrationPage resource="pacientes" searchState={busca} />;
}
