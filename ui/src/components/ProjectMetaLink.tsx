import { Link } from 'react-router-dom';
import { useProject } from '../useProject';

interface Props {
  profile: string;
}

/** Item meta chip: the resolved project's display name (falling back to the raw project id while
 * the project loads or when it can't be fetched), linking to `/projects?id=<id>` so the reader can
 * jump straight to that project's config. */
export default function ProjectMetaLink({ profile }: Props) {
  const project = useProject(profile);
  const name = project.data?.name ?? profile;
  return <Link to={`/projects?id=${encodeURIComponent(profile)}`}>{name}</Link>;
}
