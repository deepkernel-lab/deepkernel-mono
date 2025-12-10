import { useEffect, useState } from 'react';
import { getContainers } from '../api';
import { Container } from '../types';
import { ContainerTable } from '../components/ContainerTable';
import { KpiCards } from '../components/KpiCards';
import { useNavigate } from 'react-router-dom';

export function DashboardPage() {
  const [containers, setContainers] = useState<Container[]>([]);
  const navigate = useNavigate();

  useEffect(() => {
    getContainers().then(setContainers);
  }, []);

  return (
    <div className="space-y-4">
      <KpiCards containers={containers} />
      <ContainerTable containers={containers} onSelect={(id) => navigate(`/container/${encodeURIComponent(id)}`)} />
    </div>
  );
}

