import logoLight from "@/assets/logo-light.svg";
import logoDark from "@/assets/logo-dark.svg";
import { useTheme } from "@/hooks/use-theme";

interface Props {
  size?: number;
  className?: string;
}

export const HestiaMark = ({ size = 32, className }: Props) => {
  const { theme } = useTheme();
  const src = theme === "dark" ? logoDark : logoLight;
  return (
    <img
      src={src}
      alt="HESTIA flame mark"
      width={size}
      height={size}
      style={{ height: size, width: "auto" }}
      className={className}
    />
  );
};
