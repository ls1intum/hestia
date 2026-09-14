import { useState } from "react";
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
import { KeyRound, LogOut, Shield, TriangleAlert, User } from "lucide-react";
import { apiToken, signOut } from "@/lib/api/api-client";
import { useMe, useResetOnSignOut } from "@/hooks/data/use-me";
import { LinkTumIdDialog } from "./LinkTumIdDialog";
import { AccessKeyDialog } from "./AccessKeyDialog";

/**
 * Identity, the account's access key, and sign-out.
 *
 * Shows the TUM ID once linked, and nudges for it while it isn't: accounts are
 * created anonymously, and an account with no TUM ID has nothing for a TUM login
 * to match on, so it will not carry its exams across the switch to TUM sign-in.
 */
export const UserMenu = () => {
  const { data: me } = useMe();
  const resetCache = useResetOnSignOut();
  const [linkOpen, setLinkOpen] = useState(false);
  const [keyOpen, setKeyOpen] = useState(false);

  if (!me) return null;

  const handleSignOut = () => {
    // Clear cached exams before dropping the token, so the next person to use this
    // browser cannot be served the previous account's data from cache.
    resetCache();
    signOut();
  };

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="sm" className="gap-2" aria-label="Account">
            <User className="h-4 w-4" />
            <span className="hidden sm:inline">
              {me.has_tum_id ? me.external_id : "Account"}
            </span>
            {!me.has_tum_id && <TriangleAlert className="h-3.5 w-3.5 text-hestia-warning" />}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-64">
          <DropdownMenuLabel className="font-normal">
            <p className="text-sm font-medium">
              {me.has_tum_id ? me.external_id : "Unnamed account"}
            </p>
            <p className="text-xs text-hestia-text-muted">
              {me.quota.parse_remaining}/{me.quota.parse_limit} parses ·{" "}
              {me.quota.solve_remaining}/{me.quota.solve_limit} solves left today
            </p>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />

          {!me.has_tum_id && (
            <DropdownMenuItem onClick={() => setLinkOpen(true)} className="cursor-pointer">
              <TriangleAlert className="mr-2 h-4 w-4" />
              Add your TUM ID
            </DropdownMenuItem>
          )}
          <DropdownMenuItem onClick={() => setKeyOpen(true)} className="cursor-pointer">
            <KeyRound className="mr-2 h-4 w-4" />
            Access key
          </DropdownMenuItem>
          {me.is_admin && (
            <DropdownMenuItem asChild>
              <Link to="/admin" className="cursor-pointer">
                <Shield className="mr-2 h-4 w-4" />
                Admin
              </Link>
            </DropdownMenuItem>
          )}
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={handleSignOut} className="cursor-pointer">
            <LogOut className="mr-2 h-4 w-4" />
            Sign out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <LinkTumIdDialog open={linkOpen} onOpenChange={setLinkOpen} />
      <AccessKeyDialog open={keyOpen} onOpenChange={setKeyOpen} token={apiToken()} />
    </>
  );
};
