import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Link } from "react-router-dom";
import { LogOut, Shield, User } from "lucide-react";
import { signOut } from "@/lib/api/api-client";
import { useMe, useResetOnSignOut } from "@/hooks/data/use-me";

/** "3/20 parses left" with a limit, "4 parses today" without one (limit 0). */
function usage(used: number, remaining: number, limit: number, what: string): string {
  return limit > 0 ? `${remaining}/${limit} ${what} left` : `${used} ${what} today`;
}

/** Identity, the daily usage and sign-out. */
export const UserMenu = () => {
  const { data: me } = useMe();
  const resetCache = useResetOnSignOut();

  if (!me) return null;

  const handleSignOut = () => {
    // Clear cached exams first, so the next person to use this browser cannot be served
    // the previous account's data from cache.
    resetCache();
    void signOut();
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" className="gap-2" aria-label="Account">
          <User className="h-4 w-4" />
          <span className="hidden sm:inline">{me.display_name ?? "Account"}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel className="font-normal">
          <p className="text-sm font-medium">{me.display_name ?? "Signed in"}</p>
          <p className="text-xs text-hestia-text-muted">
            {usage(me.quota.parse_used, me.quota.parse_remaining, me.quota.parse_limit, "parses")} ·{" "}
            {usage(me.quota.solve_used, me.quota.solve_remaining, me.quota.solve_limit, "solves")}
          </p>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {me.is_admin && (
          <DropdownMenuItem asChild>
            <Link to="/admin" className="cursor-pointer">
              <Shield className="mr-2 h-4 w-4" />
              Admin
            </Link>
          </DropdownMenuItem>
        )}
        <DropdownMenuItem onClick={handleSignOut} className="cursor-pointer">
          <LogOut className="mr-2 h-4 w-4" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};
